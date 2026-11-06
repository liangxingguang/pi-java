package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.core.source.SourceInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W4（B277b）：扩展包 changelog 启动展示——pi
 * {@code getExtensionChangelogUpdates}（interactive-mode.ts:1384-1432）与
 * {@code showStartupNoticesIfNeeded} 的扩展段（:882-901）对位。
 */
class ExtensionChangelogsTest {

    @TempDir
    Path tempRoot;

    private static SettingsAccessors accessors(Path dir) {
        return SettingsManager.withStorage(new FileSettingsStorage(dir, dir)).accessors();
    }

    /** 造一个带 package.json + CHANGELOG.md 的包目录。 */
    private Path packageDir(String name, String version, String changelog) throws Exception {
        var dir = Files.createDirectories(tempRoot.resolve(name));
        Files.writeString(dir.resolve("package.json"),
            "{\"name\":\"" + name + "\",\"version\":\"" + version + "\"}");
        if (changelog != null) {
            Files.writeString(dir.resolve("CHANGELOG.md"), changelog);
        }
        return dir;
    }

    private static SourceInfo packageInfo(Path dir, String source) {
        return new SourceInfo(dir.resolve("ext.jar").toString(), source,
            SourceInfo.Scope.USER, SourceInfo.Origin.PACKAGE, dir.toString(),
            dir.resolve("CHANGELOG.md").toString());
    }

    @Test
    void resumedSessionShowsNothing() throws Exception {
        var dir = packageDir("pkg", "2.0.0", "## [2.0.0]\n\n- new");
        var settings = accessors(tempRoot);

        assertThat(ExtensionChangelogs.compute(
            List.of(packageInfo(dir, "npm:pkg")), settings, true)).isEmpty();
        // 会话有消息臂在一切之前——不写回。
        assertThat(settings.getExtensionChangelogVersion(
            ExtensionChangelogs.packageKey(packageInfo(dir, "npm:pkg")))).isNull();
    }

    @Test
    void freshInstallRecordsVersionWithoutShowing() throws Exception {
        var dir = packageDir("pkg", "2.0.0", "## [2.0.0]\n\n- new");
        var settings = accessors(tempRoot);
        var info = packageInfo(dir, "npm:pkg");

        assertThat(ExtensionChangelogs.compute(List.of(info), settings, false)).isEmpty();
        assertThat(settings.getExtensionChangelogVersion(ExtensionChangelogs.packageKey(info)))
            .isEqualTo("2.0.0");
    }

    @Test
    void upgradeShowsNewEntriesWithPackageNamePrefixed() throws Exception {
        var dir = packageDir("pkg", "2.0.0",
            "## [2.0.0]\n\n- newer\n\n## [1.5.0]\n\n- older\n");
        var settings = accessors(tempRoot);
        var info = packageInfo(dir, "npm:pkg");
        settings.setExtensionChangelogVersion(ExtensionChangelogs.packageKey(info), "1.0.0");

        var updates = ExtensionChangelogs.compute(List.of(info), settings, false);

        assertThat(updates).hasSize(1);
        assertThat(updates.getFirst().name()).isEqualTo("pkg");
        assertThat(updates.getFirst().version()).isEqualTo("2.0.0");
        // 每条 "## x.y.z" 标题前插包名（pi :1415 `## ${name} `）。
        assertThat(updates.getFirst().markdown())
            .contains("## pkg [2.0.0]").contains("## pkg [1.5.0]")
            .contains("- newer").contains("- older");
        assertThat(settings.getExtensionChangelogVersion(ExtensionChangelogs.packageKey(info)))
            .isEqualTo("2.0.0");
    }

    @Test
    void noNewEntriesRecordsNothing() throws Exception {
        var dir = packageDir("pkg", "2.0.0", "## [2.0.0]\n\n- new");
        var settings = accessors(tempRoot);
        var info = packageInfo(dir, "npm:pkg");
        settings.setExtensionChangelogVersion(ExtensionChangelogs.packageKey(info), "9.0.0");

        assertThat(ExtensionChangelogs.compute(List.of(info), settings, false)).isEmpty();
        // 无新条目臂不写回（pi 同：回写在两臂内）。
        assertThat(settings.getExtensionChangelogVersion(ExtensionChangelogs.packageKey(info)))
            .isEqualTo("9.0.0");
    }

    @Test
    void onlyPackageOriginWithBaseDirAndChangelogIsConsidered() throws Exception {
        var dir = packageDir("pkg", "2.0.0", "## [2.0.0]\n\n- new");
        var settings = accessors(tempRoot);
        var topLevel = new SourceInfo(dir.resolve("ext.jar").toString(), "local",
            SourceInfo.Scope.USER, SourceInfo.Origin.TOP_LEVEL, dir.toString(),
            dir.resolve("CHANGELOG.md").toString());
        var noChangelog = new SourceInfo(dir.resolve("ext.jar").toString(), "npm:pkg",
            SourceInfo.Scope.USER, SourceInfo.Origin.PACKAGE, dir.toString(), null);

        assertThat(ExtensionChangelogs.compute(List.of(topLevel, noChangelog), settings, false))
            .isEmpty();
    }

    @Test
    void samePackageTwoScopesDedupedBySourceAndScope() throws Exception {
        var dir = packageDir("pkg", "2.0.0", "## [2.0.0]\n\n- new");
        var settings = accessors(tempRoot);
        var user = packageInfo(dir, "npm:pkg");
        var project = new SourceInfo(PackageKeyOne(dir), "npm:pkg",
            SourceInfo.Scope.PROJECT, SourceInfo.Origin.PACKAGE, dir.toString(),
            dir.resolve("CHANGELOG.md").toString());
        settings.setExtensionChangelogVersion(ExtensionChangelogs.packageKey(user), "1.0.0");
        settings.setExtensionChangelogVersion(ExtensionChangelogs.packageKey(project), "1.0.0");

        // 同 source 不同 scope ⇒ 不同身份键 ⇒ 各出一条（pi getSourcePackageKey 含 scope）。
        var updates = ExtensionChangelogs.compute(List.of(user, project), settings, false);
        assertThat(updates).hasSize(2);
        // 同一身份重复出现 ⇒ 只出一条。
        assertThat(ExtensionChangelogs.compute(List.of(user, user), settings, true)).isEmpty();
    }

    private static String PackageKeyOne(Path dir) {
        return dir.resolve("ext.jar").toString();
    }

    @Test
    void condensedTextMatchesPiLine() {
        var updates = List.of(
            new ExtensionChangelogs.Update("alpha", "1.2.0", "## alpha [1.2.0]"),
            new ExtensionChangelogs.Update("beta", "0.3.1", "## beta [0.3.1]"));

        assertThat(ExtensionChangelogs.condensedText(updates))
            .isEqualTo("Updated alpha to v1.2.0.\nUpdated beta to v0.3.1.");
    }

    @Test
    void packageKeyIsStableJsonWithFixedKeyOrder() {
        var info = new SourceInfo("/x/ext.jar", "npm:pkg", SourceInfo.Scope.PROJECT,
            SourceInfo.Origin.PACKAGE, "/x", null);

        assertThat(ExtensionChangelogs.packageKey(info))
            .isEqualTo("{\"source\":\"npm:pkg\",\"scope\":\"project\"}");
    }

    @Test
    void missingPackageJsonIsSkipped() throws Exception {
        var dir = Files.createDirectories(tempRoot.resolve("broken"));
        Files.writeString(dir.resolve("CHANGELOG.md"), "## [2.0.0]\n\n- new");
        var settings = accessors(tempRoot);

        assertThat(ExtensionChangelogs.compute(List.of(packageInfo(dir, "npm:broken")),
            settings, false)).isEmpty();
    }

    @Test
    void settingsAccessorsRoundTripChangelogVersions() {
        var dir = tempRoot.resolve("settings");
        var settings = accessors(dir);

        assertThat(settings.getExtensionChangelogVersion("k")).isNull();
        settings.setExtensionChangelogVersion("k", "1.0.0");

        assertThat(settings.getExtensionChangelogVersion("k")).isEqualTo("1.0.0");
        // 重载后仍在（全局域落盘）。
        var reloaded = SettingsManager.withStorage(new FileSettingsStorage(dir, dir));
        assertThat(reloaded.accessors().getExtensionChangelogVersion("k")).isEqualTo("1.0.0");
    }

    @Test
    void sessionShareDestinationRoundTripsAndFallsBackToAuto() {
        var dir = tempRoot.resolve("share-destination");
        var settings = accessors(dir);

        assertThat(settings.getSessionShareDestination()).isEqualTo("auto");
        settings.setSessionShareDestination("radius");
        assertThat(settings.getSessionShareDestination()).isEqualTo("radius");
        settings.setSessionShareDestination("gist");
        assertThat(settings.getSessionShareDestination()).isEqualTo("gist");
        // 非法值回落 auto（pi :820-826 三值校验）。
        settings.setSessionShareDestination("bogus");
        assertThat(settings.getSessionShareDestination()).isEqualTo("auto");
    }

    @Test
    void selfUpdateCommandIsGlobalOnlyDefinitionsCopy() {
        var dir = tempRoot.resolve("self-update");
        var manager = SettingsManager.withStorage(new FileSettingsStorage(dir, dir));
        var settings = manager.accessors();

        assertThat(settings.getSelfUpdateCommand()).isNull();
        manager.global().selfUpdateCommand = new java.util.ArrayList<>(List.of("echo", "{version}"));
        manager.markModified("selfUpdateCommand");

        var command = settings.getSelfUpdateCommand();
        assertThat(command).containsExactly("echo", "{version}");
        // 防御性拷贝：返回表不可变，改它不动设置。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> command.set(0, "changed"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(settings.getSelfUpdateCommand()).containsExactly("echo", "{version}");
    }

    @Test
    void declaredSettingsKeysSerialize() throws Exception {
        var dir = tempRoot.resolve("keys");
        var manager = SettingsManager.withStorage(new FileSettingsStorage(dir, dir));
        manager.accessors().setSessionShareDestination("gist");
        manager.accessors().setExtensionChangelogVersion("k", "1.0.0");
        manager.global().selfUpdateCommand = List.of("true");
        manager.markModified("selfUpdateCommand");
        manager.flush();

        var text = Files.readString(dir.resolve("settings.json"));
        assertThat(text).contains("\"sessionShareDestination\"")
            .contains("\"extensionChangelogVersions\"")
            .contains("\"selfUpdateCommand\"");
    }
}
