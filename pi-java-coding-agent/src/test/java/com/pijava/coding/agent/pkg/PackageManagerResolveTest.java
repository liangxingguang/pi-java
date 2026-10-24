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
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * 批 18 T5（docs/29 §3/§7）：resolve() 总装——五档优先级＋canonicalize 去重＋
 * autoload=false delta＋onMissing 三臂＋内置臂 override＋信任门。
 *
 * <p>全部走临时 home/cwd/agentDir（批 15 教训＋裁决⑨）。</p>
 */
class PackageManagerResolveTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path homeDir;

    private InMemorySettingsStorage storage;
    private SettingsManager settings;
    private FakeNpmResolver npmResolver;
    private PackageManager manager;

    /** 记录安装调用的假 resolver（不触网）。 */
    private static final class FakeNpmResolver implements NpmResolver {
        final List<String> installed = new ArrayList<>();

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
            installed.add(source.spec());
            var target = installRoot.resolve("node_modules").resolve(source.name());
            Files.createDirectories(target);
            Files.writeString(target.resolve("package.json"),
                "{\"name\":\"" + source.name() + "\",\"version\":\"1.0.0\"}");
            return target;
        }
    }

    @BeforeEach
    void setUp() {
        storage = new InMemorySettingsStorage();
        settings = SettingsManager.withStorage(storage);
        npmResolver = new FakeNpmResolver();
        manager = newManager(List.of());
    }

    private PackageManager newManager(List<String> builtins) {
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        var layout = new InstallLayout(resolver);
        return new PackageManager(resolver, settings, builtins, npmResolver, layout,
            new GitPackageStore(new GitRunner(), layout, null), homeDir);
    }

    private void writeGlobal(Settings s) {
        storage.writeGlobal(s);
        settings.reload();
    }

    private void writeProject(Settings s) {
        storage.writeProject(s);
        settings.reload();
    }

    private static Path skill(Path base, String name) throws IOException {
        var file = base.resolve("skills").resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# " + name);
        return file;
    }

    @Test
    void localPackageContributesSkillResources() throws Exception {
        var pkgDir = Files.createDirectories(cwd.resolve("pkg"));
        var skillFile = skill(pkgDir, "foo");
        var global = new Settings();
        global.packages = List.of(PackageSource.of(pkgDir.toString()));
        writeGlobal(global);

        var resolved = manager.resolve(null);

        assertThat(resolved.skills())
            .anySatisfy(r -> {
                assertThat(r.path()).isEqualTo(skillFile.toString());
                assertThat(r.enabled()).isTrue();
                assertThat(r.metadata().origin()).isEqualTo(PathMetadata.Origin.PACKAGE);
            });
    }

    @Test
    void fiveTierPrecedenceOrdersPaths() throws Exception {
        // project settings 条目（rank 0）＜ user settings 条目（2）＜ 包（4）。
        var projectSkill = skill(cwd.resolve(".pi-java"), "fromProjSettings");
        var userSkill = skill(agentDir, "fromUserSettings");
        var pkgDir = Files.createDirectories(cwd.resolve("pkg5"));
        var pkgSkill = skill(pkgDir, "fromPackage");

        var project = new Settings();
        project.skills = List.of("skills/fromProjSettings");
        writeProject(project);
        var global = new Settings();
        global.skills = List.of("skills/fromUserSettings");
        global.packages = List.of(PackageSource.of(pkgDir.toString()));
        writeGlobal(global);

        var resolved = manager.resolve(null);
        var paths = resolved.skills().stream().map(ResolvedResource::path).toList();

        assertThat(paths).containsSubsequence(
            projectSkill.toString(), userSkill.toString(), pkgSkill.toString());
    }

    @Test
    void projectAutoDiscoveryOutranksUserAndTrustGate() throws Exception {
        var projectSkill = skill(cwd.resolve(".pi-java"), "autoProj");
        var userSkill = skill(agentDir, "autoUser");

        var resolved = manager.resolve(null);
        var paths = resolved.skills().stream().map(ResolvedResource::path).toList();

        assertThat(paths).containsSubsequence(projectSkill.toString(), userSkill.toString());

        // 信任门：untrusted ⇒ project 自动发现消失（pi :2478 对位）。
        settings.setProjectTrusted(false);
        var untrusted = manager.resolve(null);
        assertThat(untrusted.skills().stream().map(ResolvedResource::path))
            .doesNotContain(projectSkill.toString())
            .contains(userSkill.toString());
    }

    @Test
    void sameCanonicalPathFirstWins() throws Exception {
        // 同一文件同时被 project settings 条目与 project 自动发现命中 ⇒ 只出现一次，
        // 先到者（rank 0 settings 条目）胜。
        var file = skill(cwd.resolve(".pi-java"), "dup");
        var project = new Settings();
        project.skills = List.of("skills/dup");
        writeProject(project);

        var resolved = manager.resolve(null);
        var hits = resolved.skills().stream()
            .filter(r -> r.path().equals(file.toString())).toList();

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).metadata().source()).isEqualTo("local");
        assertThat(hits.get(0).metadata().scope()).isEqualTo(SourceScope.PROJECT);
    }

    @Test
    void autoloadFalseProjectDeltaTogglesUserPackage() throws Exception {
        // delta 语义（pi :2313-2329 + dedupe :1754）：project autoload=false 条目
        // 只点名覆写；资源从 user 同包安装收。
        var pkgDir = Files.createDirectories(cwd.resolve("pkgDelta"));
        var keep = skill(pkgDir, "keep");
        var drop = skill(pkgDir, "drop");

        var global = new Settings();
        global.packages = List.of(PackageSource.of(pkgDir.toString()));
        writeGlobal(global);
        var project = new Settings();
        project.packages = List.of(PackageSource.object(
            pkgDir.toString(), false, null, List.of("!drop"), null, null));
        writeProject(project);

        var resolved = manager.resolve(null);

        assertThat(resolved.skills().stream()
                .filter(r -> r.path().equals(keep.toString())))
            .singleElement().extracting(ResolvedResource::enabled).isEqualTo(true);
        assertThat(resolved.skills().stream()
                .filter(r -> r.path().equals(drop.toString())))
            .singleElement().extracting(ResolvedResource::enabled).isEqualTo(false);
    }

    @Test
    void onMissingNullInstallsDirectly() {
        var global = new Settings();
        global.packages = List.of(PackageSource.of("npm:ghostpkg"));
        writeGlobal(global);

        manager.resolve(null);

        assertThat(npmResolver.installed).containsExactly("ghostpkg");
    }

    @Test
    void onMissingSkipSkipsInstall() {
        var global = new Settings();
        global.packages = List.of(PackageSource.of("npm:ghostpkg"));
        writeGlobal(global);

        var resolved = manager.resolve(source -> MissingSourceAction.SKIP);

        assertThat(npmResolver.installed).isEmpty();
        assertThat(resolved.skills()).isEmpty();
    }

    @Test
    void onMissingErrorThrowsVerbatim() {
        var global = new Settings();
        global.packages = List.of(PackageSource.of("npm:ghostpkg"));
        writeGlobal(global);

        // pi :1309 模型可见文案逐字。
        assertThatThrownBy(() -> manager.resolve(source -> MissingSourceAction.ERROR))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Missing source: npm:ghostpkg");
    }

    @Test
    void onMissingInstallInstalls() {
        var global = new Settings();
        global.packages = List.of(PackageSource.of("npm:ghostpkg"));
        writeGlobal(global);

        manager.resolve(source -> MissingSourceAction.INSTALL);

        assertThat(npmResolver.installed).containsExactly("ghostpkg");
    }

    @Test
    void builtinExtensionOverrideLadder() {
        manager = newManager(List.of("mcp"));

        // 默认：enabled，user 作用域。
        var resolved = manager.resolve(null);
        assertThat(resolved.extensions())
            .anySatisfy(r -> {
                assertThat(r.path()).isEqualTo("builtin:mcp");
                assertThat(r.enabled()).isTrue();
                assertThat(r.metadata().source()).isEqualTo("builtin");
            });

        // user `-builtin:mcp` 关停。
        var global = new Settings();
        global.extensions = List.of("-builtin:mcp");
        writeGlobal(global);
        assertThat(manager.resolve(null).extensions().stream()
                .filter(r -> r.path().equals("builtin:mcp")))
            .singleElement().extracting(ResolvedResource::enabled).isEqualTo(false);

        // project `+builtin:mcp` 压过（scope 翻 PROJECT）。
        var project = new Settings();
        project.extensions = List.of("+builtin:mcp");
        writeProject(project);
        var overridden = manager.resolve(null).extensions().stream()
            .filter(r -> r.path().equals("builtin:mcp")).findFirst().orElseThrow();
        assertThat(overridden.enabled()).isTrue();
        assertThat(overridden.metadata().scope()).isEqualTo(SourceScope.PROJECT);
    }

    @Test
    void settingsExtensionsPatternDisablesDiscovered() throws Exception {
        var extDir = Files.createDirectories(agentDir.resolve("extensions"));
        Files.writeString(extDir.resolve("foo.jar"), "jar-bytes");
        var global = new Settings();
        global.extensions = List.of("!foo.jar");
        writeGlobal(global);

        var resolved = manager.resolve(null);

        assertThat(resolved.extensions().stream()
                .filter(r -> r.path().endsWith("foo.jar")))
            .singleElement().extracting(ResolvedResource::enabled).isEqualTo(false);
    }

    @Test
    void resolveExtensionSourcesTemporaryLocalDir() throws Exception {
        // -e 本地目录走 temporary resolve：原样过（pi resolveExtensionSources）。
        var extDir = Files.createDirectories(cwd.resolve("ext"));
        Files.writeString(extDir.resolve("index.jar"), "jar");

        var resolved = manager.resolveExtensionSources(List.of(extDir.toString()), false, true);

        // pi resolveLocalExtensionSource：目录无清单/约定目录 ⇒ 目录本身作扩展路径收
        //（index.jar 入口在加载臂解析，T6 消费面对位）。
        assertThat(resolved.extensions())
            .anySatisfy(r -> {
                assertThat(r.path()).isEqualTo(extDir.toString());
                assertThat(r.metadata().scope()).isEqualTo(SourceScope.TEMPORARY);
            });
    }
}
