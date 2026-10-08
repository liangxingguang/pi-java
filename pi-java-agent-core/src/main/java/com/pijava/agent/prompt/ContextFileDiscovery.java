package com.pijava.agent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.pijava.agent.prompt.SystemPromptOptions.ContextFile;

/**
 * Discovery of project instruction files (AGENTS.md / CLAUDE.md) — pi
 * {@code loadProjectContextFiles}（{@code coding-agent/src/core/resource-loader.ts}）。
 *
 * <p>Order of the returned list: <b>global (agentDir) → farthest ancestor →
 * cwd (nearest)</b>. Each directory contributes at most one file, the first
 * candidate in {@link #CANDIDATES} that is a regular file. Canonical paths
 * de-duplicate files seen at multiple walk positions. Unreadable files are
 * skipped (warning only), matching pi.</p>
 */
public final class ContextFileDiscovery {

    /**
     * pi {@code loadContextFileFromDir} candidates（{@code resource-loader.ts:185}），
     * 顺序即优先级。
     */
    public static final List<String> CANDIDATES = List.of(
        "AGENTS.override.md", "AGENTS.md", "AGENTS.MD",
        "CLAUDE.md", "CLAUDE.MD");

    /** BOM stripped by pi's {@code stripBom}. */
    private static final char BOM = '﻿';

    private ContextFileDiscovery() {}

    /**
     * pi {@code loadProjectContextFiles}。
     *
     * @param cwd      walk start (resolved like pi's resolvedCwd)
     * @param agentDir global scope; {@code null} ⇒ no global file
     */
    public static List<ContextFile> discover(Path cwd, Path agentDir) {
        var files = new ArrayList<ContextFile>();
        var seen = new LinkedHashSet<Path>();

        if (agentDir != null) {
            var global = loadFromDir(agentDir).orElse(null);
            if (global != null && seen.add(canonical(Path.of(global.path())))) {
                files.add(global);
            }
        }

        var ancestors = new ArrayList<ContextFile>();
        // A linked worktree nested inside the main worktree shares its repository scope, so the
        // main root's file would be applied twice. It is skipped during the walk (pi
        // findShadowedContextFile).
        var shadowed = findShadowedContextFile(cwd);
        var current = cwd;
        while (true) {
            var file = loadFromDir(current).orElse(null);
            var isShadowed = file != null && shadowed != null
                    && canonical(Path.of(file.path())).equals(shadowed);
            if (file != null && !isShadowed && seen.add(canonical(Path.of(file.path())))) {
                // unshift: nearest dir goes to the end ⇒ farthest ancestor first
                ancestors.add(0, file);
            }
            var parent = current.getParent();
            if (parent == null) {
                break;
            }
            current = parent;
        }
        files.addAll(ancestors);
        return List.copyOf(files);
    }

    /**
     * pi {@code loadContextFileFromDir}：按 {@link #CANDIDATES} 顺序取第一个
     * 存在的普通文件。{@code path} 是命中文件的完整路径。
     */
    static Optional<ContextFile> loadFromDir(Path dir) {
        for (var name : CANDIDATES) {
            var file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                var content = stripBom(Files.readString(file, StandardCharsets.UTF_8));
                return Optional.of(new ContextFile(file.toString(), content));
            } catch (IOException e) {
                // pi: console warning, treat as miss
                System.getLogger(ContextFileDiscovery.class.getName())
                    .log(System.Logger.Level.WARNING,
                        "Could not read " + file + ": " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** De-duplication key: real path, falling back to absolute path. */
    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    /** Where a directory sits in git (pi {@code findGitPaths}, footer-data-provider.ts:16-48). */
    private record GitPaths(Path repoDir, Path commonGitDir, Path headPath) {
    }

    /**
     * Walk up from {@code start} looking for {@code .git}. Reads only the filesystem — never
     * spawns git. A linked worktree's {@code commondir} points back at the main working tree.
     */
    private static @Nullable GitPaths findGitPaths(Path start) {
        var dir = start;
        while (true) {
            var dotGit = dir.resolve(".git");
            if (Files.exists(dotGit)) {
                try {
                    if (Files.isRegularFile(dotGit)) {
                        var content = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
                        if (content.startsWith("gitdir: ")) {
                            var gitDir = dir.resolve(content.substring("gitdir: ".length()).trim())
                                    .normalize();
                            var headPath = gitDir.resolve("HEAD");
                            if (!Files.exists(headPath)) {
                                return null;
                            }
                            var commonDirFile = gitDir.resolve("commondir");
                            Path commonGitDir;
                            if (Files.exists(commonDirFile)) {
                                // Path.resolve keeps an absolute target, which commondir often is.
                                commonGitDir = gitDir.resolve(
                                        Files.readString(commonDirFile, StandardCharsets.UTF_8).trim())
                                        .normalize();
                            } else {
                                commonGitDir = gitDir;
                            }
                            return new GitPaths(dir, commonGitDir, headPath);
                        }
                    } else if (Files.isDirectory(dotGit)) {
                        var headPath = dotGit.resolve("HEAD");
                        if (!Files.exists(headPath)) {
                            return null;
                        }
                        return new GitPaths(dir, dotGit, headPath);
                    }
                } catch (IOException error) {
                    return null;
                }
            }
            var parent = dir.getParent();
            if (parent == null) {
                return null;
            }
            dir = parent;
        }
    }

    /**
     * The main worktree's context file that a nested linked worktree shadows
     * (pi {@code findShadowedContextFile}, resource-loader.ts:205-227).
     *
     * @return the canonical path to skip in the walk, or {@code null} when nothing is shadowed
     */
    private static @Nullable Path findShadowedContextFile(Path cwd) {
        var gitPaths = findGitPaths(cwd);
        if (gitPaths == null) {
            return null;
        }
        var commonGitDir = canonical(gitPaths.commonGitDir());
        var worktreeRoot = canonical(gitPaths.repoDir());
        // pi: dirname(commonGitDir). commonGitDir is the ".git" entry itself, so its
        // parent is the main worktree root — not the grandparent.
        var mainRepoRoot = commonGitDir.getParent();
        if (mainRepoRoot == null) {
            return null;
        }
        // The worktree must be strictly inside the main worktree: equal (an ordinary repo)
        // or unrelated (a sibling worktree) does not shadow. After resolving real paths,
        // path-segment startsWith is pi's `startsWith(mainRepoRoot + sep)`.
        if (!worktreeRoot.startsWith(mainRepoRoot) || worktreeRoot.equals(mainRepoRoot)) {
            return null;
        }
        // The common dir must itself be a checked-out main worktree. This rejects a bare
        // layout (no .git next to the common dir) and a submodule (no commondir pointing back).
        if (!canonical(mainRepoRoot.resolve(".git")).equals(commonGitDir)) {
            return null;
        }
        // The shadowed file is the main root's copy *of the worktree's own file*: the basename
        // comes from the worktree, not from whatever candidate the main root happens to have.
        // With AGENTS.override.md in the worktree and AGENTS.md in the main root, nothing is
        // shadowed and both load.
        var worktreeContext = loadFromDir(worktreeRoot).orElse(null);
        if (worktreeContext == null) {
            return null;
        }
        return mainRepoRoot.resolve(Path.of(worktreeContext.path()).getFileName());
    }
}
