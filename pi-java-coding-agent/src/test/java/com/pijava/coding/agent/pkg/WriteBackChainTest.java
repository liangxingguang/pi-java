package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;

/**
 * 批 19 T1（docs/30 §3/§7）：settings 写回链——身份去重（npm 忽略版本／
 * git SSH⇄HTTPS／local 相对⇄绝对）、原位替换保过滤、追加序、remove 无匹配
 * ⇒ false、{@code ConfiguredPackage.filtered} 对象形判定。
 */
class WriteBackChainTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path homeDir;

    private InMemorySettingsStorage storage;
    private SettingsManager settings;
    private PackageManager manager;

    @BeforeEach
    void setUp() {
        storage = new InMemorySettingsStorage();
        settings = SettingsManager.withStorage(storage);
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        var layout = new InstallLayout(resolver);
        manager = new PackageManager(resolver, settings, List.of(),
            new PackageManagerResolveTestNpmStub(), layout,
            new GitPackageStore(new GitRunner(), layout, null), homeDir);
    }

    /** 最小 npm stub（写回链不消费网络面）。 */
    private static final class PackageManagerResolveTestNpmStub implements NpmResolver {
        @Override
        public List<String> versions(String name) {
            return List.of();
        }

        @Override
        public String latestSatisfying(String name, String range) {
            return null;
        }

        @Override
        public Path install(ParsedSource.NpmSource source, Path installRoot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void uninstall(String name, Path installRoot) {
            // no-op
        }
    }

    private void seedGlobalPackages(PackageSource... packages) {
        var s = new Settings();
        s.packages = List.of(packages);
        storage.writeGlobal(s);
        settings.reload();
    }

    @Test
    void dedupesNpmIgnoringVersion() {
        seedGlobalPackages(PackageSource.of("npm:foo@1.0.0"));

        var changed = manager.addSourceToSettings("npm:foo@2.0.0", false);

        assertThat(changed).isTrue();
        assertThat(settings.global().packages)
            .containsExactly(PackageSource.of("npm:foo@2.0.0"));   // 原位替换，不追加
    }

    @Test
    void sameIdentitySameStringIsNoOp() {
        seedGlobalPackages(PackageSource.of("npm:foo"));

        assertThat(manager.addSourceToSettings("npm:foo", false)).isFalse();
        assertThat(settings.global().packages).hasSize(1);
    }

    @Test
    void gitSshAndHttpsShareIdentity() {
        seedGlobalPackages(PackageSource.of("git:git@github.com:user/repo"));

        var changed = manager.addSourceToSettings("https://github.com/user/repo", false);

        assertThat(changed).isTrue();
        assertThat(settings.global().packages)
            .containsExactly(PackageSource.of("https://github.com/user/repo"));
    }

    @Test
    void localRelativeAndAbsoluteShareIdentity() {
        seedGlobalPackages(PackageSource.of("ext"));   // 相对 agentDir

        var absolute = agentDir.resolve("ext").toString();
        // 同身份同归一串（"ext"）⇒ false 且不复制
        assertThat(manager.addSourceToSettings(absolute, false)).isFalse();
        assertThat(settings.global().packages).containsExactly(PackageSource.of("ext"));
    }

    @Test
    void inPlaceReplaceKeepsObjectFormFilters() {
        seedGlobalPackages(PackageSource.object("npm:foo", null, List.of("+ext/a"),
            null, null, null));

        manager.addSourceToSettings("npm:foo@1.2.3", false);

        var pkg = settings.global().packages.get(0);
        assertThat(pkg.stringForm()).isFalse();
        assertThat(pkg.source()).isEqualTo("npm:foo@1.2.3");
        assertThat(pkg.extensions()).containsExactly("+ext/a");
    }

    @Test
    void distinctSourcesAppendInOrder() {
        seedGlobalPackages(PackageSource.of("npm:a"));

        manager.addSourceToSettings("npm:b", false);
        manager.addSourceToSettings("git:github.com/u/r", false);

        assertThat(settings.global().packages).extracting(PackageSource::source)
            .containsExactly("npm:a", "npm:b", "git:github.com/u/r");
    }

    @Test
    void removeSourceNoMatchReturnsFalse() {
        seedGlobalPackages(PackageSource.of("npm:a"));

        assertThat(manager.removeSourceFromSettings("npm:absent", false)).isFalse();
        assertThat(settings.global().packages).hasSize(1);
    }

    @Test
    void removeSourceMatchesByIdentity() {
        seedGlobalPackages(PackageSource.of("npm:a@1.0.0"), PackageSource.of("npm:b"));

        assertThat(manager.removeSourceFromSettings("npm:a@9.9.9", false)).isTrue();
        assertThat(settings.global().packages).containsExactly(PackageSource.of("npm:b"));
    }

    @Test
    void projectScopeWritesProjectSettings() {
        manager.addSourceToSettings("npm:foo", true);

        assertThat(settings.project().packages).containsExactly(PackageSource.of("npm:foo"));
        assertThat(settings.global().packages).isNull();
    }

    @Test
    void listConfiguredPackagesMarksObjectFormFiltered() {
        seedGlobalPackages(
            PackageSource.of("npm:bare"),
            PackageSource.object("npm:filtered", null, List.of("+x"), null, null, null));

        var configured = manager.listConfiguredPackages();

        assertThat(configured).extracting(ConfiguredPackage::source)
            .containsExactly("npm:bare", "npm:filtered");
        assertThat(configured).extracting(ConfiguredPackage::filtered)
            .containsExactly(false, true);
        assertThat(configured).extracting(ConfiguredPackage::scope)
            .containsExactly(SourceScope.USER, SourceScope.USER);
        assertThat(configured).extracting(ConfiguredPackage::installedPath)
            .containsExactly(null, null);   // 未安装 ⇒ null
    }

    @Test
    void listConfiguredPackagesReportsInstalledPath() throws IOException {
        seedGlobalPackages(PackageSource.of("npm:foo"));
        var installed = agentDir.resolve("npm").resolve("node_modules").resolve("foo");
        Files.createDirectories(installed);

        var configured = manager.listConfiguredPackages();

        assertThat(configured.get(0).installedPath()).isEqualTo(installed.toString());
    }
}
