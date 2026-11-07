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

import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * L5 W5 §6.2（docs/25；pi package-manager.ts:1408-1449/:2137-2162）：{@code -e}
 * temporary 源的装载期刷新——git 带 ref 也刷新（ref 可指移动分支），只有「commit
 * 哈希且已检出」才跳过网络；npm 未固定版本源发现 registry 新版本即重装，失败
 * 静默保留缓存。
 */
class TemporarySourceRefreshTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path homeDir;

    private RecordingGit git;
    private StubNpm npm;
    private InstallLayout layout;
    private GitPackageStore store;

    /** rev-parse HEAD 应答 {@link #head}；记录所有 run 调用。 */
    private static final class RecordingGit extends GitRunner {
        final List<List<String>> ran = new ArrayList<>();
        final List<List<String>> captured = new ArrayList<>();
        volatile String head = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        volatile boolean failCapture;

        @Override
        public void run(List<String> args, Path cwd) {
            ran.add(args);
        }

        @Override
        public String capture(List<String> args, Path cwd, long timeoutMs) {
            captured.add(args);
            if (failCapture) {
                throw new GitCommandException("capture failed");
            }
            if (args.contains("--abbrev-ref")) {
                return "origin/main";
            }
            return head;
        }
    }

    private static final class StubNpm implements NpmResolver {
        final List<String> installed = new ArrayList<>();
        final List<String> queried = new ArrayList<>();
        volatile String latest = "2.0.0";
        volatile boolean failQuery;
        volatile boolean failInstall;

        @Override
        public List<String> versions(String name) {
            return List.of("1.0.0", "2.0.0");
        }

        @Override
        public String latestSatisfying(String name, String range) throws IOException {
            queried.add(name + ":" + range);
            if (failQuery) {
                throw new IOException("registry down");
            }
            return latest;
        }

        @Override
        public Path install(NpmSource source, Path installRoot) throws IOException {
            if (failInstall) {
                throw new IOException("install failed");
            }
            installed.add(source.spec());
            var target = installRoot.resolve("node_modules").resolve(source.name());
            Files.createDirectories(target);
            Files.writeString(target.resolve("package.json"),
                "{\"name\":\"" + source.name() + "\",\"version\":\"" + latest + "\"}");
            return target;
        }

        @Override
        public void uninstall(String name, Path installRoot) {
            // 本套件不消费。
        }
    }

    @BeforeEach
    void setUp() {
        var resolver = new SourceResolver(cwd, agentDir, () -> true);
        layout = new InstallLayout(resolver);
        git = new RecordingGit();
        npm = new StubNpm();
        store = new GitPackageStore(git, layout, null);
    }

    // ── git 臂 ───────────────────────────────────────────────────

    @Test
    void branchRefAlwaysRefreshes() throws Exception {
        var source = gitSource("main");
        Files.createDirectories(layout.gitInstallPath(source, SourceScope.TEMPORARY).resolve(".git"));

        store.refreshTemporaryGitSource(source, "git:local.test/u/r@main");

        // 分支 ref 可移动 ⇒ 不做「已检出即跳过」预检，直接 fetch/reset。
        assertThat(git.ran).anyMatch(args -> args.contains("fetch"));
    }

    @Test
    void commitRefAlreadyCheckedOutSkipsNetwork() throws Exception {
        var sha = "abcdef1234567890abcdef1234567890abcdef12";
        var source = gitSource(sha);
        Files.createDirectories(layout.gitInstallPath(source, SourceScope.TEMPORARY).resolve(".git"));
        git.head = sha + "\n";

        store.refreshTemporaryGitSource(source, "git:local.test/u/r@" + sha);

        assertThat(git.ran).isEmpty();   // 预检命中 ⇒ 零 spawn。
    }

    @Test
    void commitRefMismatchStillRefreshes() throws Exception {
        var source = gitSource("abcdef1");
        Files.createDirectories(layout.gitInstallPath(source, SourceScope.TEMPORARY).resolve(".git"));
        git.head = "9999999";

        store.refreshTemporaryGitSource(source, "git:local.test/u/r@abcdef1");

        assertThat(git.ran).anyMatch(args -> args.contains("fetch"));
    }

    @Test
    void refreshFailureKeepsCacheSilently() throws Exception {
        var source = gitSource("abcdef1");
        Files.createDirectories(layout.gitInstallPath(source, SourceScope.TEMPORARY).resolve(".git"));
        git.failCapture = true;

        // 不抛（pi catch {}）。
        store.refreshTemporaryGitSource(source, "git:local.test/u/r@abcdef1");

        assertThat(git.ran).isEmpty();
    }

    // ── npm 臂（PackageManager.resolvePackageSources 内联）────────

    @Test
    void temporaryNpmRefreshesToLatestWhenUnpinned() throws Exception {
        var manager = managerWith(npm);
        var installed = layout.npmInstallPath(npmSource("foo"), SourceScope.TEMPORARY);
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("package.json"),
            "{\"name\":\"foo\",\"version\":\"1.0.0\"}");

        manager.resolveExtensionSources(List.of("npm:foo"), false, true);

        assertThat(npm.installed).containsExactly("foo@latest");
    }

    @Test
    void temporaryNpmWithRangeSpecRefreshesUsingSpec() throws Exception {
        var manager = managerWith(npm);
        var installed = layout.npmInstallPath(npmSource("foo", "^1.0.0"), SourceScope.TEMPORARY);
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("package.json"),
            "{\"name\":\"foo\",\"version\":\"1.0.0\"}");
        npm.latest = "1.1.0";

        manager.resolveExtensionSources(List.of("npm:foo@^1.0.0"), false, true);

        // 有版本 spec ⇒ 用原 spec（pi :1421-1423）；精确版本（pinned）不走刷新臂。
        assertThat(npm.installed).containsExactly("foo@^1.0.0");
    }

    @Test
    void temporaryNpmRefreshFailureKeepsCache() throws Exception {
        var manager = managerWith(npm);
        var installed = layout.npmInstallPath(npmSource("foo"), SourceScope.TEMPORARY);
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("package.json"),
            "{\"name\":\"foo\",\"version\":\"1.0.0\"}");
        npm.failInstall = true;

        // 不抛（pi catch）。
        var resolved = manager.resolveExtensionSources(List.of("npm:foo"), false, true);

        assertThat(resolved.installFailures()).isEmpty();
        assertThat(npm.installed).isEmpty();
    }

    @Test
    void temporaryNpmRegistryFailureSkipsRefresh() throws Exception {
        var manager = managerWith(npm);
        var installed = layout.npmInstallPath(npmSource("foo"), SourceScope.TEMPORARY);
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("package.json"),
            "{\"name\":\"foo\",\"version\":\"1.0.0\"}");
        npm.failQuery = true;

        manager.resolveExtensionSources(List.of("npm:foo"), false, true);

        assertThat(npm.installed).isEmpty();
    }

    @Test
    void upToDateTemporaryNpmIsNotRefreshed() throws Exception {
        var manager = managerWith(npm);
        var installed = layout.npmInstallPath(npmSource("foo"), SourceScope.TEMPORARY);
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("package.json"),
            "{\"name\":\"foo\",\"version\":\"2.0.0\"}");
        npm.latest = "2.0.0";

        manager.resolveExtensionSources(List.of("npm:foo"), false, true);

        assertThat(npm.installed).isEmpty();
    }

    // ── 小件 ─────────────────────────────────────────────────────

    private PackageManager managerWith(NpmResolver resolver) {
        var sourceResolver = new SourceResolver(cwd, agentDir, () -> true);
        var installLayout = new InstallLayout(sourceResolver);
        return new PackageManager(sourceResolver,
            com.pijava.coding.agent.core.SettingsManager.withStorage(
                new com.pijava.coding.agent.core.InMemorySettingsStorage()),
            List.of(), resolver, installLayout,
            new GitPackageStore(git, installLayout, null), homeDir);
    }

    private static GitSource gitSource(String ref) {
        return new GitSource("git:local.test/u/r", "local.test", "u/r", ref, ref != null);
    }

    private static NpmSource npmSource(String name) {
        return npmSource(name, null);
    }

    private static NpmSource npmSource(String name, String version) {
        return new NpmSource(version == null ? name : name + "@" + version, name, version,
            Semvers.validRange(version), SourceParser.isExactNpmVersion(version));
    }
}
