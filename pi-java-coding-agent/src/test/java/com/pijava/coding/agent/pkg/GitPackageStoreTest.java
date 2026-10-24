package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ProgressEvent.Action;
import com.pijava.coding.agent.pkg.ProgressEvent.Type;

/**
 * 批 18 T4（docs/29 §3/§7）：git spawn 实现——本地裸仓夹具（不触网）：
 * clone/checkout ref/ensureGitRef 幂等/marker 中断恢复/updateGit 双臂/
 * removeGit 空父目录修剪/{@code GIT_TERMINAL_PROMPT=0} 断言/temporary 刷新。
 *
 * <p>无 git 环境 ⇒ 全套 skip（{@code Assumptions}，docs/29 §7 守卫）。</p>
 */
class GitPackageStoreTest {

    @TempDir
    static Path shared;

    @TempDir
    Path agentDir;

    private static Path origin;
    private static Path work;

    @BeforeAll
    static void gitAvailable() {
        Assumptions.assumeTrue(gitWorks(), "git not on PATH");
    }

    private static boolean gitWorks() {
        try {
            var process = new ProcessBuilder("git", "--version").start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // ── 夹具：裸仓 origin ＋ 工作仓 work（两提交一 tag）────────────

    private void initFixture() throws Exception {
        if (origin != null) {
            return;
        }
        origin = shared.resolve("origin.git");
        work = shared.resolve("work");
        git(null, "init", "--bare", "-b", "main", origin.toString());
        git(null, "clone", origin.toString(), work.toString());
        git(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "init");
        write(work, "skills/s.md", "# v1");
        git(work, "add", ".");
        git(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "v1");
        git(work, "tag", "v1");
        write(work, "skills/s.md", "# v2");
        git(work, "add", ".");
        git(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "v2");
        git(work, "push", "-u", "origin", "main");
        git(work, "push", "origin", "v1");
    }

    private GitSource source(String ref) {
        return new GitSource(origin.toString(), "local.test", "u/r", ref, ref != null);
    }

    private GitPackageStore store(List<ProgressEvent> events) {
        var layout = new InstallLayout(new SourceResolver(shared, agentDir, () -> true));
        return new GitPackageStore(new GitRunner(), layout,
            events == null ? null : events::add);
    }

    @Test
    void installGitClonesAndChecksOutRef() throws Exception {
        initFixture();
        var store = store(null);

        store.installGit(source("v1"), SourceScope.USER);

        var target = installTarget(store, "v1");
        assertThat(Files.readString(target.resolve("skills/s.md"))).isEqualTo("# v1");
    }

    private Path installTarget(GitPackageStore store, String ref) {
        var layout = new InstallLayout(new SourceResolver(shared, agentDir, () -> true));
        return layout.gitInstallPath(source(ref), SourceScope.USER);
    }

    @Test
    void installGitExistingUnpinnedRefreshesToUpstream() throws Exception {
        initFixture();
        var store = store(null);
        store.installGit(source(null), SourceScope.USER);
        assertThat(Files.readString(installTarget(store, null).resolve("skills/s.md")))
            .isEqualTo("# v2");

        // 推第三版 ⇒ 再次 install 走 ensureGitRef 更新臂。
        write(work, "skills/s.md", "# v3");
        git(work, "add", ".");
        git(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "v3");
        git(work, "push", "origin", "main");
        try {
            store.installGit(source(null), SourceScope.USER);
            assertThat(Files.readString(installTarget(store, null).resolve("skills/s.md")))
                .isEqualTo("# v3");
        } finally {
            // 夹具共享：回滚 origin 到 v2 供其他用例。
            git(work, "-c", "user.email=t@t", "-c", "user.name=t",
                "reset", "--hard", "HEAD~1");
            git(work, "push", "--force", "origin", "main");
        }
    }

    @Test
    void ensureGitRefIdempotentClearsMarker() throws Exception {
        initFixture();
        var store = store(null);
        store.installGit(source("v1"), SourceScope.USER);
        var target = installTarget(store, "v1");
        var marker = GitPackageStore.updateMarkerPath(target);

        assertThat(marker).doesNotExist();
        // 中断现场：手写 marker ⇒ 等头再 ensure ⇒ 修复臂清 marker。
        Files.writeString(marker, "");
        store.ensureGitRef(target, List.of("git", "fetch", "origin", "v1"), "FETCH_HEAD");
        assertThat(marker).doesNotExist();
        assertThat(Files.readString(target.resolve("skills/s.md"))).isEqualTo("# v1");
    }

    @Test
    void localUpdateTargetPrefersUpstreamArm() throws Exception {
        initFixture();
        var store = store(null);
        store.installGit(source(null), SourceScope.USER);

        var target = store.getLocalGitUpdateTarget(installTarget(store, null));

        assertThat(target.ref()).isEqualTo("@{upstream}");
        assertThat(target.fetchArgs()).contains(
            "+refs/heads/main:refs/remotes/origin/main");
    }

    @Test
    void localUpdateTargetFallsBackToOriginHeadArm() throws Exception {
        initFixture();
        var store = store(null);
        store.installGit(source(null), SourceScope.USER);
        var target = installTarget(store, null);
        // 摘掉 upstream 跟踪 ⇒ catch 臂（remote set-head ⇒ origin/HEAD）。
        git(target, "branch", "--unset-upstream");

        var update = store.getLocalGitUpdateTarget(target);

        assertThat(update.ref()).isEqualTo("origin/HEAD");
        assertThat(update.fetchArgs()).contains(
            "+refs/heads/main:refs/remotes/origin/main");
        // 恢复跟踪供其他用例。
        git(target, "branch", "--set-upstream-to=origin/main", "main");
    }

    @Test
    void remoteGitHeadMatchesOrigin() throws Exception {
        initFixture();
        var store = store(null);
        store.installGit(source(null), SourceScope.USER);

        var remote = store.getRemoteGitHead(installTarget(store, null));
        var local = new GitRunner().capture(
            List.of("git", "rev-parse", "origin/main"), installTarget(store, null),
            GitRunner.NETWORK_TIMEOUT_MS);

        assertThat(remote).matches("[0-9a-f]{40}").isEqualTo(local);
    }

    @Test
    void remoteCaptureSetsGitTerminalPrompt() throws Exception {
        initFixture();
        var recording = new GitRunner() {
            final List<List<String>> lsRemoteCalls = new ArrayList<>();
            final List<Map<String, String>> lsRemoteEnv = new ArrayList<>();

            @Override
            public String capture(List<String> args, Path cwd, long timeoutMs,
                    Map<String, String> extraEnv) {
                if (args.contains("ls-remote")) {
                    lsRemoteCalls.add(args);
                    lsRemoteEnv.add(extraEnv);
                }
                return super.capture(args, cwd, timeoutMs, extraEnv);
            }
        };
        var store = new GitPackageStore(recording,
            new InstallLayout(new SourceResolver(shared, agentDir, () -> true)), null);
        store.installGit(source(null), SourceScope.USER);

        store.getRemoteGitHead(installTarget(store, null));

        // pi :1685-1687：远程命令（ls-remote）统一带 GIT_TERMINAL_PROMPT=0。
        assertThat(recording.lsRemoteCalls).isNotEmpty();
        assertThat(recording.lsRemoteEnv)
            .allSatisfy(env -> assertThat(env)
                .containsEntry("GIT_TERMINAL_PROMPT", "0"));
    }

    @Test
    void removeGitPrunesEmptyParents() throws Exception {
        initFixture();
        var store = store(null);
        store.installGit(source("v1"), SourceScope.USER);
        var target = installTarget(store, "v1");
        var hostDir = target.getParent();

        store.removeGit(source("v1"), SourceScope.USER);

        assertThat(target).doesNotExist();
        // pruneEmptyGitParents：空 host 目录删，installRoot 保留。
        assertThat(hostDir).doesNotExist();
        assertThat(agentDir.resolve("git")).exists();
    }

    @Test
    void temporaryRefreshEmitsProgressAndUpdates() throws Exception {
        initFixture();
        var events = new ArrayList<ProgressEvent>();
        var store = store(events);
        store.installGit(source(null), SourceScope.TEMPORARY);

        write(work, "skills/s.md", "# v4");
        git(work, "add", ".");
        git(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "v4");
        git(work, "push", "origin", "main");
        try {
            store.refreshTemporaryGitSource(source(null), "git:local.test/u/r");

            var layout = new InstallLayout(new SourceResolver(shared, agentDir, () -> true));
            var checkout = layout.gitInstallPath(source(null), SourceScope.TEMPORARY);
            assertThat(Files.readString(checkout.resolve("skills/s.md"))).isEqualTo("# v4");
            assertThat(events).extracting(ProgressEvent::type)
                .containsExactly(Type.START, Type.COMPLETE);
            assertThat(events.get(0).action()).isEqualTo(Action.PULL);
            assertThat(events.get(0).message()).isEqualTo("Refreshing git:local.test/u/r...");
        } finally {
            git(work, "-c", "user.email=t@t", "-c", "user.name=t",
                "reset", "--hard", "HEAD~1");
            git(work, "push", "--force", "origin", "main");
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────

    private static void write(Path dir, String rel, String content) throws IOException {
        var path = dir.resolve(rel);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void git(Path cwd, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command)
            .directory(cwd == null ? null : cwd.toFile())
            .redirectErrorStream(true)
            .start();
        var output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
