package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.PackageInstallWarnings;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * L5 W5 §6.1（docs/25；pi package-manager.ts:1371-1454、resource-loader.ts:105-133，
 * issue #11088）：配置包的安装失败<b>不阻塞启动</b>——记 failure、跳过其资源、
 * 其余包照常解析；显式 {@code onMissing=error} 仍抛。
 */
class PackageInstallFailureTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path homeDir;

    private InMemorySettingsStorage storage;
    private SettingsManager settings;
    private FlakyNpmResolver npm;
    private PackageManager manager;

    /** 按名失败/成功的假 resolver（不触网）。 */
    private static final class FlakyNpmResolver implements NpmResolver {
        final List<String> installed = new ArrayList<>();
        final List<String> failing = new ArrayList<>();

        @Override
        public List<String> versions(String name) {
            return List.of("1.0.0");
        }

        @Override
        public String latestSatisfying(String name, String range) {
            return "1.0.0";
        }

        @Override
        public Path install(NpmSource source, Path installRoot) throws IOException {
            if (failing.contains(source.name())) {
                throw new IOException("failed with code 1");
            }
            installed.add(source.spec());
            var target = installRoot.resolve("node_modules").resolve(source.name());
            Files.createDirectories(target);
            Files.writeString(target.resolve("package.json"),
                "{\"name\":\"" + source.name() + "\",\"version\":\"1.0.0\"}");
            Files.createDirectories(target.resolve("skills").resolve(source.name()));
            Files.writeString(target.resolve("skills").resolve(source.name()).resolve("SKILL.md"),
                "# " + source.name());
            return target;
        }

        @Override
        public void uninstall(String name, Path installRoot) {
            // resolve 测试不消费卸载臂。
        }
    }

    @BeforeEach
    void setUp() {
        storage = new InMemorySettingsStorage();
        settings = SettingsManager.withStorage(storage);
        npm = new FlakyNpmResolver();
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        var layout = new InstallLayout(resolver);
        manager = new PackageManager(resolver, settings, List.of(), npm, layout,
            new GitPackageStore(new GitRunner(), layout, null), homeDir);
    }

    private void writeGlobal(List<String> sources) {
        var s = new Settings();
        s.packages = sources.stream().map(PackageSource::of).toList();
        storage.writeGlobal(s);
        settings.reload();
    }

    private void writeProject(List<String> sources) {
        settings.setProjectTrusted(true);
        var s = new Settings();
        s.packages = sources.stream().map(PackageSource::of).toList();
        storage.writeProject(s);
        settings.reload();
    }

    @Test
    void brokenPackageDoesNotAbortStartup() {
        npm.failing.add("broken");
        writeGlobal(List.of("npm:broken", "npm:good"));

        var resolved = manager.resolve(null);

        assertThat(npm.installed).containsExactly("good");
        assertThat(resolved.installFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.source()).isEqualTo("npm:broken");
            assertThat(failure.scope()).isEqualTo(SourceScope.USER);
            assertThat(failure.message()).isEqualTo("failed with code 1");
        });
        // 好包资源照常在。
        assertThat(resolved.skills()).anySatisfy(r ->
            assertThat(r.path()).endsWith("good" + java.io.File.separator + "SKILL.md"));
    }

    @Test
    void skippedSourceRecordsNoFailure() {
        writeGlobal(List.of("npm:missing"));

        var resolved = manager.resolve(source -> MissingSourceAction.SKIP);

        assertThat(npm.installed).isEmpty();
        assertThat(resolved.installFailures()).isEmpty();
    }

    @Test
    void explicitErrorStillThrows() {
        writeGlobal(List.of("npm:missing"));

        // pi :1309 逐字：onMissing=error 不走安装失败臂。
        assertThatThrownBy(() -> manager.resolve(source -> MissingSourceAction.ERROR))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Missing source: npm:missing");
    }

    @Test
    void extensionSourceFailuresAreReportedToo() {
        npm.failing.add("broken");

        var resolved = manager.resolveExtensionSources(List.of("npm:broken"), false, true);

        assertThat(resolved.installFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.source()).isEqualTo("npm:broken");
            assertThat(failure.scope()).isEqualTo(SourceScope.TEMPORARY);
        });
    }

    // ── 启动警告文案（pi resource-loader.ts:105-120 逐字；品牌 pi-java）───

    @Test
    void userScopeWarningSuggestRemoveWithoutLocalFlag() {
        var warning = PackageInstallWarnings.format(new PackageInstallFailure(
            "npm:broken", SourceScope.USER, "failed with code 1"));

        assertThat(warning).isEqualTo("Extension package \"npm:broken\": failed to install "
            + "(failed with code 1). Run \"pi-java remove npm:broken\" to remove it from settings.json.");
    }

    @Test
    void projectScopeWarningAddsLocalFlag() {
        var warning = PackageInstallWarnings.format(new PackageInstallFailure(
            "./ext", SourceScope.PROJECT, "boom"));

        assertThat(warning).isEqualTo("Extension package \"./ext\": failed to install (boom). "
            + "Run \"pi-java remove ./ext --local\" to remove it from settings.json.");
    }

    @Test
    void temporaryScopeWarningSuggestsRetry() {
        var warning = PackageInstallWarnings.format(new PackageInstallFailure(
            "npm:broken", SourceScope.TEMPORARY, "boom"));

        assertThat(warning).isEqualTo(
            "Extension package \"npm:broken\": failed to install (boom). "
                + "Check the source and try again.");
    }

    @Test
    void warningsAreDedupedBySource() {
        var resolved = new ResolvedPaths(List.of(), List.of(), List.of(), List.of(),
            List.of(new PackageInstallFailure("npm:x", SourceScope.USER, "a")));
        var cli = new ResolvedPaths(List.of(), List.of(), List.of(), List.of(),
            List.of(new PackageInstallFailure("npm:x", SourceScope.TEMPORARY, "a")));

        assertThat(PackageInstallWarnings.collect(resolved, cli))
            .containsExactly(PackageInstallWarnings.format(
                new PackageInstallFailure("npm:x", SourceScope.TEMPORARY, "a")));
    }
}
