package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * 批 19 T3（docs/30 §3/§7）：{@code checkForAvailableUpdates}——temporary/local/
 * pinned/未安装排除；npm 查询失败 ⇒ <b>false</b>（与 shouldUpdate 反向钉死）；
 * git 本地 HEAD vs 远程 HEAD；project 压 user 去重。
 */
class CheckUpdatesTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path homeDir;

    private InMemorySettingsStorage storage;
    private SettingsManager settings;
    private StubNpm npm;
    private StubGit git;
    private InstallLayout layout;
    private PackageManager manager;

    private static final class StubNpm implements NpmResolver {
        final List<String> failing = new ArrayList<>();
        volatile String latest = "2.0.0";

        @Override
        public List<String> versions(String name) {
            return List.of("1.0.0", "2.0.0");
        }

        @Override
        public String latestSatisfying(String name, String range) throws IOException {
            if (failing.contains(name)) {
                throw new IOException("registry down");
            }
            return latest;
        }

        @Override
        public Path install(NpmSource source, Path installRoot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void uninstall(String name, Path installRoot) {
            // no-op
        }
    }

    private static final class StubGit extends GitRunner {
        static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        /** 非 null ⇒ rev-parse HEAD 应答此值（默认 SHA_A）。 */
        volatile String localHead;
        /** 非 null ⇒ ls-remote 应答此值（默认 SHA_B）。 */
        volatile String remoteHead;
        final List<List<String>> remoteCalls =
            Collections.synchronizedList(new ArrayList<>());

        @Override
        public String capture(List<String> args, Path cwd, long timeoutMs) {
            if (args.contains("--abbrev-ref")) {
                return "origin/main";
            }
            return localHead != null ? localHead : SHA_A;
        }

        @Override
        public String remoteCapture(List<String> args, Path cwd) {
            remoteCalls.add(args);
            return (remoteHead != null ? remoteHead : SHA_B) + "\trefs/heads/main";
        }
    }

    @BeforeEach
    void setUp() {
        storage = new InMemorySettingsStorage();
        settings = SettingsManager.withStorage(storage);
        npm = new StubNpm();
        git = new StubGit();
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        layout = new InstallLayout(resolver);
        manager = new PackageManager(resolver, settings, List.of(), npm, layout,
            new GitPackageStore(git, layout, null), homeDir);
    }

    private void seedUser(String... sources) {
        var s = new Settings();
        s.packages = java.util.Arrays.stream(sources).map(PackageSource::of).toList();
        storage.writeGlobal(s);
        settings.reload();
    }

    private void seedProject(String... sources) {
        var s = new Settings();
        s.packages = java.util.Arrays.stream(sources).map(PackageSource::of).toList();
        storage.writeProject(s);
        settings.reload();
    }

    private void markNpmInstalled(String name, String version) throws IOException {
        markNpmInstalled(name, version, SourceScope.USER);
    }

    private void markNpmInstalled(String name, String version, SourceScope scope)
            throws IOException {
        var parsed = (NpmSource) SourceParser.parseSource("npm:" + name);
        var dir = layout.npmInstallPath(parsed, scope);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"),
            "{\"name\":\"" + name + "\",\"version\":\"" + version + "\"}");
    }

    @Test
    void newerNpmVersionIsReported() throws IOException {
        seedUser("npm:foo");
        markNpmInstalled("foo", "1.0.0");
        npm.latest = "2.0.0";

        var updates = manager.checkForAvailableUpdates();

        assertThat(updates).containsExactly(
            new PackageUpdate("npm:foo", "foo", PackageUpdate.Type.NPM, SourceScope.USER));
    }

    @Test
    void queryFailureMeansNoReportedUpdate() throws IOException {
        // 与 shouldUpdateNpmSource（失败⇒true 保守）反向钉死（pi :1526-1529）。
        seedUser("npm:foo");
        markNpmInstalled("foo", "1.0.0");
        npm.failing.add("foo");

        assertThat(manager.checkForAvailableUpdates()).isEmpty();
    }

    @Test
    void exclusionsApply() throws IOException {
        seedUser("npm:pinned@1.0.0", "./local/dir", "npm:absent");
        Files.createDirectories(cwd.resolve("local/dir"));

        assertThat(manager.checkForAvailableUpdates()).isEmpty();
        // pinned/local 不查询；absent 未安装不查询
    }

    @Test
    void upToDateNpmIsNotReported() throws IOException {
        seedUser("npm:foo");
        markNpmInstalled("foo", "2.0.0");
        npm.latest = "2.0.0";

        assertThat(manager.checkForAvailableUpdates()).isEmpty();
    }

    @Test
    void gitRemoteHeadMismatchIsReported() throws IOException {
        seedUser("git:github.com/u/r");
        var parsed = (ParsedSource.GitSource) SourceParser.parseSource("git:github.com/u/r");
        Files.createDirectories(layout.gitInstallPath(parsed, SourceScope.USER));
        git.localHead = StubGit.SHA_A;
        git.remoteHead = StubGit.SHA_B;

        var updates = manager.checkForAvailableUpdates();

        assertThat(updates).containsExactly(new PackageUpdate(
            "git:github.com/u/r", "github.com/u/r", PackageUpdate.Type.GIT, SourceScope.USER));
    }

    @Test
    void gitHeadsEqualIsNotReported() throws IOException {
        seedUser("git:github.com/u/r");
        var parsed = (ParsedSource.GitSource) SourceParser.parseSource("git:github.com/u/r");
        Files.createDirectories(layout.gitInstallPath(parsed, SourceScope.USER));
        git.localHead = StubGit.SHA_A;
        git.remoteHead = StubGit.SHA_A;

        assertThat(manager.checkForAvailableUpdates()).isEmpty();
    }

    @Test
    void pinnedGitIsExcludedFromCheck() throws IOException {
        seedUser("git:github.com/u/r@v1.0.0");
        var parsed = (ParsedSource.GitSource) SourceParser.parseSource(
            "git:github.com/u/r@v1.0.0");
        Files.createDirectories(layout.gitInstallPath(parsed, SourceScope.USER));

        assertThat(manager.checkForAvailableUpdates()).isEmpty();
        assertThat(git.remoteCalls).isEmpty();   // pinned ⇒ 不查远程
    }

    @Test
    void projectEntryWinsDedupeOverUser() throws IOException {
        // pi dedupe：同身份 project 赢（:1742-1763）⇒ 检查结果带 project scope。
        seedUser("npm:foo");
        seedProject("npm:foo");
        markNpmInstalled("foo", "1.0.0", SourceScope.PROJECT);
        npm.latest = "2.0.0";

        var updates = manager.checkForAvailableUpdates();

        assertThat(updates).containsExactly(
            new PackageUpdate("npm:foo", "foo", PackageUpdate.Type.NPM, SourceScope.PROJECT));
    }
}
