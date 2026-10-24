package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.pijava.coding.agent.pkg.GitRunner.GitCommandException;
import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ProgressEvent.Action;

/**
 * pi git 安装面（package-manager.ts:1583-1662/:1891-2060）逐字对位——
 * clone/checkout/ensureGitRef（marker 中断恢复）/update 目标双臂/远程 HEAD/
 * removeGit 空父目录修剪/temporary 刷新。
 *
 * <p><b>窄差 D103（裁决⑩）</b>：pi 在 clone/更新后对有 package.json 的包跑
 * {@code npm install --omit=dev}——java 扩展是 JAR 无 node 依赖概念，三处
 * （:1914-1917/:1985-1988/:1977-1989 修复臂）全部裁掉。</p>
 */
public final class GitPackageStore {

    private static final Pattern REMOTE_REF_HEAD = Pattern.compile("^([0-9a-f]{40})\\s+", Pattern.MULTILINE);
    private static final Pattern REMOTE_HEAD = Pattern.compile("^([0-9a-f]{40})\\s+HEAD$", Pattern.MULTILINE);

    private final GitRunner git;
    private final InstallLayout layout;
    private volatile ProgressCallback progressCallback;

    public GitPackageStore(GitRunner git, InstallLayout layout, ProgressCallback progressCallback) {
        this.git = git;
        this.layout = layout;
        this.progressCallback = progressCallback;
    }

    /** pi {@code setProgressCallback} 对位（晚绑定，resolve 前随时可换）。 */
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    /** pi {@code getGitUpdateMarkerPath}（:1971-1973）：
     *  {@code <parent>/.<basename>.pi-update-incomplete}。 */
    public static Path updateMarkerPath(Path targetDir) {
        var parent = targetDir.getParent();
        var name = targetDir.getFileName().toString();
        return parent.resolve("." + name + ".pi-update-incomplete");
    }

    /** pi {@code installGit}（:1891-1923；npm 依赖安装臂裁，D103）。 */
    public void installGit(GitSource source, SourceScope scope) throws IOException {
        var targetDir = layout.gitInstallPath(source, scope);
        if (Files.exists(targetDir)) {
            if (source.ref() != null) {
                ensureGitRef(targetDir, List.of("git", "fetch", "origin", source.ref()), "FETCH_HEAD");
                return;
            }
            var target = getLocalGitUpdateTarget(targetDir);
            ensureGitRef(targetDir, target.fetchArgs(), target.ref());
            return;
        }
        var gitRoot = layout.gitInstallRoot(scope);
        if (gitRoot != null) {
            layout.ensureGitIgnore(gitRoot);
        }
        Files.createDirectories(targetDir.getParent());
        Files.deleteIfExists(updateMarkerPath(targetDir));

        try {
            git.run(List.of("git", "clone", source.repo(), targetDir.toString()), null);
            if (source.ref() != null) {
                git.run(List.of("git", "checkout", source.ref()), targetDir);
            }
            // D103：package.json ⇒ npm 依赖安装臂裁掉。
        } catch (GitCommandException e) {
            deleteRecursively(targetDir);
            pruneEmptyGitParents(targetDir, gitRoot);
            throw e;
        }
    }

    /** pi {@code updateGit}（:1925-1939）。 */
    public void updateGit(GitSource source, SourceScope scope) throws IOException {
        var targetDir = layout.gitInstallPath(source, scope);
        if (!Files.exists(targetDir)) {
            installGit(source, scope);
            return;
        }
        if (source.ref() != null) {
            ensureGitRef(targetDir, List.of("git", "fetch", "origin", source.ref()), "FETCH_HEAD");
            return;
        }
        var target = getLocalGitUpdateTarget(targetDir);
        ensureGitRef(targetDir, target.fetchArgs(), target.ref());
    }

    /**
     * pi {@code ensureGitRef}（:1992-2018）：fetch → 双 rev-parse 比对 →
     * marker 修复臂或「写 marker → reset --hard → clean → 删 marker」。
     * 等头无 marker 时的 npm 修复臂裁掉（D103）。
     */
    public void ensureGitRef(Path targetDir, List<String> fetchArgs, String ref) throws IOException {
        // Fetch only the ref we will reset to, avoiding unrelated branch/tag noise.
        git.run(fetchArgs, targetDir);

        var localHead = git.capture(List.of("git", "rev-parse", "HEAD"), targetDir,
            GitRunner.NETWORK_TIMEOUT_MS).trim();
        var commitRef = ref + "^{commit}";
        var targetHead = git.capture(List.of("git", "rev-parse", commitRef), targetDir,
            GitRunner.NETWORK_TIMEOUT_MS).trim();
        var markerPath = updateMarkerPath(targetDir);
        if (localHead.equals(targetHead)) {
            if (Files.exists(markerPath)) {
                cleanGitCheckout(targetDir, markerPath);
            }
            return;
        }

        Files.writeString(markerPath, "");
        git.run(List.of("git", "reset", "--hard", commitRef), targetDir);
        cleanGitCheckout(targetDir, markerPath);
    }

    /** pi {@code cleanAndInstallGitDependencies}（:1975-1990；npm 安装臂裁，D103）。 */
    private void cleanGitCheckout(Path targetDir, Path markerPath) throws IOException {
        // Clean untracked files (extensions should be pristine).
        git.run(List.of("git", "clean", "-fdx"), targetDir);
        Files.deleteIfExists(markerPath);
    }

    /** pi {@code getLocalGitUpdateTarget}（:1601-1662）双臂逐字。 */
    public GitUpdateTarget getLocalGitUpdateTarget(Path installedPath) {
        try {
            var upstream = git.capture(
                List.of("git", "rev-parse", "--abbrev-ref", "@{upstream}"),
                installedPath, GitRunner.NETWORK_TIMEOUT_MS).trim();
            if (!upstream.startsWith("origin/")) {
                throw new GitCommandException("Unsupported upstream remote: " + upstream);
            }
            var branch = upstream.substring("origin/".length());
            if (branch.isEmpty()) {
                throw new GitCommandException("Missing upstream branch name");
            }
            var head = git.capture(List.of("git", "rev-parse", "@{upstream}"),
                installedPath, GitRunner.NETWORK_TIMEOUT_MS);
            return new GitUpdateTarget("@{upstream}", head, List.of(
                "git", "fetch", "--prune", "--no-tags", "origin",
                "+refs/heads/" + branch + ":refs/remotes/origin/" + branch));
        } catch (GitCommandException e) {
            try {
                git.run(List.of("git", "remote", "set-head", "origin", "-a"), installedPath);
            } catch (GitCommandException ignored) {
                // pi :1628 .catch(() => {}) 对位
            }
            var head = git.capture(List.of("git", "rev-parse", "origin/HEAD"),
                installedPath, GitRunner.NETWORK_TIMEOUT_MS);
            String originHeadRef;
            try {
                originHeadRef = git.capture(
                    List.of("git", "symbolic-ref", "refs/remotes/origin/HEAD"),
                    installedPath, GitRunner.NETWORK_TIMEOUT_MS);
            } catch (GitCommandException ignored) {
                originHeadRef = "";
            }
            var branch = originHeadRef.trim().replaceFirst("^refs/remotes/origin/", "");
            if (!branch.isEmpty()) {
                return new GitUpdateTarget("origin/HEAD", head, List.of(
                    "git", "fetch", "--prune", "--no-tags", "origin",
                    "+refs/heads/" + branch + ":refs/remotes/origin/" + branch));
            }
            return new GitUpdateTarget("origin/HEAD", head, List.of(
                "git", "fetch", "--prune", "--no-tags", "origin",
                "+HEAD:refs/remotes/origin/HEAD"));
        }
    }

    /** pi {@code getGitUpstreamRef}（:1664-1677）。 */
    public String getGitUpstreamRef(Path installedPath) {
        try {
            var upstream = git.capture(
                List.of("git", "rev-parse", "--abbrev-ref", "@{upstream}"),
                installedPath, GitRunner.NETWORK_TIMEOUT_MS).trim();
            if (!upstream.startsWith("origin/")) {
                return null;
            }
            var branch = upstream.substring("origin/".length());
            return branch.isEmpty() ? null : "refs/heads/" + branch;
        } catch (GitCommandException e) {
            return null;
        }
    }

    /** pi {@code getRemoteGitHead}（:1583-1599）——失败抛
     *  {@code Failed to determine remote HEAD}（逐字）。 */
    public String getRemoteGitHead(Path installedPath) {
        var upstreamRef = getGitUpstreamRef(installedPath);
        if (upstreamRef != null) {
            var remoteHead = git.remoteCapture(
                List.of("git", "ls-remote", "origin", upstreamRef), installedPath);
            var match = REMOTE_REF_HEAD.matcher(remoteHead);
            if (match.find()) {
                return match.group(1);
            }
        }
        var remoteHead = git.remoteCapture(List.of("git", "ls-remote", "origin", "HEAD"),
            installedPath);
        var match = REMOTE_HEAD.matcher(remoteHead);
        if (!match.find()) {
            throw new GitCommandException("Failed to determine remote HEAD");
        }
        return match.group(1);
    }

    /** pi {@code removeGit}（:2033-2038）。 */
    public void removeGit(GitSource source, SourceScope scope) throws IOException {
        var targetDir = layout.gitInstallPath(source, scope);
        deleteRecursively(targetDir);
        Files.deleteIfExists(updateMarkerPath(targetDir));
        pruneEmptyGitParents(targetDir, layout.gitInstallRoot(scope));
    }

    /** pi {@code pruneEmptyGitParents}（:2040-2060）：向上删空父目录直到
     *  installRoot。 */
    void pruneEmptyGitParents(Path targetDir, Path installRoot) {
        if (installRoot == null) {
            return;
        }
        var resolvedRoot = installRoot.toAbsolutePath().normalize();
        var current = targetDir.toAbsolutePath().normalize().getParent();
        while (current != null && current.startsWith(resolvedRoot) && !current.equals(resolvedRoot)) {
            if (!Files.exists(current)) {
                current = current.getParent();
                continue;
            }
            try (var entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    break;
                }
            } catch (IOException e) {
                break;
            }
            try {
                deleteRecursively(current);
            } catch (IOException e) {
                break;
            }
            current = current.getParent();
        }
    }

    /** pi {@code refreshTemporaryGitSource}（:2020-2031）：temporary＋离线门＋
     *  {@code withProgress("pull", …)}；<b>失败静默保留缓存检出</b>（catch 空臂）。 */
    public void refreshTemporaryGitSource(GitSource source, String sourceStr) {
        if (PackageEnvironment.isOfflineModeEnabled()) {
            return;
        }
        try {
            withProgress(Action.PULL, sourceStr, "Refreshing " + sourceStr + "...", () ->
                updateGit(source, SourceScope.TEMPORARY));
        } catch (Exception e) {
            // Keep cached temporary checkout if refresh fails.
        }
    }

    /** pi {@code withProgress}（:903-918）：start → complete / error（message＝
     *  异常 message）＋继续抛。 */
    void withProgress(Action action, String source, String message, IoRunnable operation)
            throws IOException {
        emitProgress(ProgressEvent.start(action, source, message));
        try {
            operation.run();
            emitProgress(ProgressEvent.complete(action, source));
        } catch (IOException | RuntimeException e) {
            emitProgress(ProgressEvent.error(action, source, e.getMessage()));
            throw e;
        }
    }

    private void emitProgress(ProgressEvent event) {
        if (progressCallback != null) {
            progressCallback.onProgress(event);
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            var paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (var p : paths) {
                Files.delete(p);
            }
        }
    }

    /** pi {@code getLocalGitUpdateTarget} 输出形。 */
    public record GitUpdateTarget(String ref, String head, List<String> fetchArgs) {}

    @FunctionalInterface
    interface IoRunnable {
        void run() throws IOException;
    }
}
