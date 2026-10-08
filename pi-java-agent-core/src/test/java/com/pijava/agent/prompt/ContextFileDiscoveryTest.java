package com.pijava.agent.prompt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContextFileDiscovery} characterization — pi
 * {@code loadProjectContextFiles}（resource-loader.ts）。
 */
class ContextFileDiscoveryTest {

    @TempDir
    Path temp;

    private void write(Path dir, String name, String content) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void emptyWhenNoCandidateExists() {
        var files = ContextFileDiscovery.discover(temp, null);
        assertThat(files).isEmpty();
    }

    @Test
    void candidateOrderFirstMatchWins() throws Exception {
        write(temp, "CLAUDE.md", "claude");
        write(temp, "AGENTS.md", "agents");
        write(temp, "AGENTS.override.md", "override");

        var files = ContextFileDiscovery.discover(temp, null);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).endsWith("AGENTS.override.md");
        assertThat(files.get(0).content()).isEqualTo("override");
    }

    @Test
    void uppercaseNameCandidateIsReachable() throws Exception {
        write(temp, "AGENTS.MD", "upper");
        var files = ContextFileDiscovery.discover(temp, null);
        assertThat(files).singleElement()
            .extracting(f -> f.content()).isEqualTo("upper");
        // On a case-sensitive FS (pi's platform) the hit path is literally AGENTS.MD;
        // on case-insensitive FS (Windows) AGENTS.md resolves the same file first.
        var path = files.get(0).path();
        assertThat(path).matches(p -> p.endsWith("AGENTS.MD") || p.endsWith("AGENTS.md"));
    }

    @Test
    void directoriesAreNotRegularFilesAndSkipped() throws Exception {
        Files.createDirectory(temp.resolve("AGENTS.md"));
        write(temp, "CLAUDE.md", "claude");

        var files = ContextFileDiscovery.discover(temp, null);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).endsWith("CLAUDE.md");
    }

    @Test
    void bomIsStripped() throws Exception {
        Files.createDirectories(temp);
        Files.write(temp.resolve("AGENTS.md"),
            new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'});

        assertThat(ContextFileDiscovery.discover(temp, null))
            .singleElement()
            .extracting(f -> f.content())
            .isEqualTo("hi");
    }

    @Test
    void ancestorsOrderedFarthestToNearest() throws Exception {
        var grandparent = temp;
        var parent = grandparent.resolve("a");
        var cwd = parent.resolve("b");
        write(grandparent, "AGENTS.md", "grand");
        write(parent, "AGENTS.md", "parent");
        write(cwd, "AGENTS.md", "cwd");

        var files = ContextFileDiscovery.discover(cwd, null);

        assertThat(files).extracting(f -> f.content())
            .containsExactly("grand", "parent", "cwd");
        assertThat(files).allSatisfy(f -> assertThat(f.path()).endsWith("AGENTS.md"));
    }

    @Test
    void globalAgentDirComesFirst() throws Exception {
        var agentDir = temp.resolve("agent");
        var cwd = temp.resolve("project");
        write(agentDir, "AGENTS.md", "global");
        write(cwd, "AGENTS.md", "project");

        assertThat(ContextFileDiscovery.discover(cwd, agentDir))
            .extracting(f -> f.content())
            .containsExactly("global", "project");
    }

    @Test
    void sameFileViaSymlinkIsDeduplicated() throws Exception {
        write(temp.resolve("real"), "AGENTS.md", "x");
        var link = temp.resolve("link");
        try {
            Files.createSymbolicLink(link, temp.resolve("real"));
        } catch (Exception | LinkageError e) {
            // symlinks unsupported (e.g. Windows without privilege) — skip
            return;
        }
        // walk from link dir; the two walk positions resolve to the same real path
        var files = ContextFileDiscovery.discover(link, null);
        assertThat(files).hasSize(1);
    }

    @Test
    void unreadableCandidateIsSkippedNotThrown() throws Exception {
        // A path that is not a readable dir ⇒ loadFromDir simply finds nothing
        var nonexistent = temp.resolve("missing");
        assertThat(ContextFileDiscovery.discover(nonexistent, null)).isEmpty();
    }

    @Test
    void stripBomHelper() {
        assertThat(ContextFileDiscovery.stripBom("﻿x")).isEqualTo("x");
        assertThat(ContextFileDiscovery.stripBom("x")).isEqualTo("x");
        assertThat(ContextFileDiscovery.stripBom("")).isEmpty();
    }

    @Test
    void candidatesMatchPiOrder() {
        assertThat(ContextFileDiscovery.CANDIDATES).isEqualTo(List.of(
            "AGENTS.override.md", "AGENTS.md", "AGENTS.MD",
            "CLAUDE.md", "CLAUDE.MD"));
    }

    // ------------------------------------------------- git linked-worktree shadow

    /**
     * Build the on-disk shape of a linked worktree nested inside the main worktree, without
     * running git. Returns the worktree root ({@code main/feat}).
     *
     * <p>Layout:
     * <pre>
     *   main/.git/HEAD
     *   main/AGENTS.md                       ("main instructions")
     *   main/feat/.git                       (file: "gitdir: &lt;worktrees/feat&gt;")
     *   &lt;gitdir&gt;/HEAD
     *   &lt;gitdir&gt;/commondir                    ("../../")
     *   main/feat/AGENTS.md                  ("feat instructions")
     * </pre>
     */
    private Path nestedLinkedWorktree() throws Exception {
        var main = temp.resolve("main");
        var mainGit = main.resolve(".git");
        Files.createDirectories(mainGit);
        Files.writeString(mainGit.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
        Files.writeString(main.resolve("AGENTS.md"), "main instructions", StandardCharsets.UTF_8);

        var feat = main.resolve("feat");
        Files.createDirectories(feat);
        var worktrees = mainGit.resolve("worktrees").resolve("feat");
        Files.createDirectories(worktrees);
        Files.writeString(worktrees.resolve("HEAD"), "ref: refs/heads/feat\n", StandardCharsets.UTF_8);
        // ../../ from main/.git/worktrees/feat is main/.git — the common dir is the ".git"
        // entry, not the worktree root.
        Files.writeString(worktrees.resolve("commondir"), "../../\n", StandardCharsets.UTF_8);
        Files.writeString(feat.resolve(".git"),
                "gitdir: " + worktrees + "\n", StandardCharsets.UTF_8);
        Files.writeString(feat.resolve("AGENTS.md"), "feat instructions", StandardCharsets.UTF_8);
        return feat;
    }

    @Test
    void nestedLinkedWorktreeShadowsTheMainContextFile() throws Exception {
        var feat = nestedLinkedWorktree();

        var files = ContextFileDiscovery.discover(feat, null);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo(feat.resolve("AGENTS.md").toString());
        assertThat(files.get(0).content()).isEqualTo("feat instructions");
    }

    @Test
    void shadowComparisonSurvivesASymlinkedCwd() throws Exception {
        // `git worktree add` writes the gitdir in realpath form while cwd may still be a
        // symlinked path (macOS /tmp -> /private/tmp). The shadowed path is returned
        // canonicalized, so the walk's own (possibly symlinked) paths only match it after
        // canonicalization — comparing raw path strings would miss the main repo's file.
        var main = temp.resolve("main");
        nestedLinkedWorktree();
        var link = temp.resolve("link");
        try {
            Files.createSymbolicLink(link, main);
        } catch (Exception | LinkageError e) {
            return; // symlinks unsupported (e.g. Windows without privilege)
        }

        var files = ContextFileDiscovery.discover(link.resolve("feat"), null);

        assertThat(files).extracting(f -> f.content()).containsExactly("feat instructions");
    }

    @Test
    void ordinaryRepoKeepsBothAncestorAndCwdFiles() throws Exception {
        var repo = temp.resolve("repo");
        write(repo, "AGENTS.md", "root");
        write(repo.resolve("sub"), "AGENTS.md", "sub");

        var files = ContextFileDiscovery.discover(repo.resolve("sub"), null);

        assertThat(files).hasSize(2);
        assertThat(files.get(0).content()).isEqualTo("root");
        assertThat(files.get(1).content()).isEqualTo("sub");
    }

    @Test
    void worktreeRootFoundByWalkingUpFromADeepCwd() throws Exception {
        // cwd sits several levels below the worktree root, so findGitPaths has to walk up to
        // find the `.git` file. Giving up at cwd would find no git dir, skip the shadow, and
        // load the main repo's file on top of the worktree's.
        var feat = nestedLinkedWorktree();
        var deep = feat.resolve("sub/deep");
        Files.createDirectories(deep);

        var files = ContextFileDiscovery.discover(deep, null);

        assertThat(files).extracting(f -> f.content()).containsExactly("feat instructions");
    }

    @Test
    void ordinaryRepoRootItselfIsNotShadowed() throws Exception {
        // The ordinary-repo counterpart of the nested-worktree case: a real .git directory at
        // the repo root makes findGitPaths return that root, and dirname(commonGitDir) equals
        // it. A missing "strictly inside" guard would land on main/AGENTS.md and skip it.
        var repo = temp.resolve("repo");
        var git = repo.resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
        write(repo, "AGENTS.md", "root");
        write(repo.resolve("sub"), "AGENTS.md", "sub");

        var files = ContextFileDiscovery.discover(repo, null);

        assertThat(files).extracting(f -> f.content()).containsExactly("root");
    }

    @Test
    void aLinkedWorktreeWithoutCommondirDoesNotShadow() throws Exception {
        var feat = nestedLinkedWorktree();
        var worktrees = temp.resolve("main/.git/worktrees/feat");
        Files.delete(worktrees.resolve("commondir"));

        var files = ContextFileDiscovery.discover(feat, null);

        // commonGitDir is the worktree git dir itself; its parent is not a checked-out main
        // worktree, so nothing matches the shadow rules and the walk is a normal ancestor walk.
        assertThat(files).hasSize(2);
        assertThat(files.get(0).content()).isEqualTo("main instructions");
        assertThat(files.get(1).content()).isEqualTo("feat instructions");
    }

    @Test
    void aWorktreeGitDirWithoutHeadIsIgnored() throws Exception {
        var feat = nestedLinkedWorktree();
        var worktrees = temp.resolve("main/.git/worktrees/feat");
        Files.delete(worktrees.resolve("HEAD"));

        // findGitPaths gives up, so there is no shadow; the walk still sees both AGENTS files.
        var files = ContextFileDiscovery.discover(feat, null);

        assertThat(files).hasSize(2);
    }

    @Test
    void aDotGitFileWithoutTheGitdirPrefixIsIgnored() throws Exception {
        // The .git file's content is a perfectly valid worktree gitdir path — but without the
        // "gitdir: " prefix pi refuses to parse it and keeps walking up to main/.git. Parsing it
        // anyway would land on the nested-worktree layout and shadow main/AGENTS.md.
        var main = temp.resolve("main");
        var mainGit = main.resolve(".git");
        Files.createDirectories(mainGit);
        Files.writeString(mainGit.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
        Files.writeString(main.resolve("AGENTS.md"), "main instructions", StandardCharsets.UTF_8);

        var feat = main.resolve("feat");
        Files.createDirectories(feat);
        var worktrees = mainGit.resolve("worktrees/feat");
        Files.createDirectories(worktrees);
        Files.writeString(worktrees.resolve("HEAD"), "ref: refs/heads/feat\n", StandardCharsets.UTF_8);
        Files.writeString(worktrees.resolve("commondir"), "../../\n", StandardCharsets.UTF_8);
        // No "gitdir: " prefix — the bare path only.
        Files.writeString(feat.resolve(".git"), worktrees + "\n", StandardCharsets.UTF_8);
        Files.writeString(feat.resolve("AGENTS.md"), "feat instructions", StandardCharsets.UTF_8);

        var files = ContextFileDiscovery.discover(feat, null);

        assertThat(files).extracting(f -> f.content())
            .containsExactly("main instructions", "feat instructions");
    }

    @Test
    void noContextFileInTheMainRootMeansNothingIsShadowed() throws Exception {
        var feat = nestedLinkedWorktree();
        Files.delete(temp.resolve("main/AGENTS.md"));

        var files = ContextFileDiscovery.discover(feat, null);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).content()).isEqualTo("feat instructions");
    }

    @Test
    void shadowFollowsTheWorktreeBasenameNotTheMainRoots() throws Exception {
        var feat = nestedLinkedWorktree();
        // The worktree resolves to AGENTS.override.md while the main root's file is AGENTS.md.
        // The basename comes from the worktree, so main/AGENTS.override.md is the shadow target
        // and the main root's AGENTS.md is untouched by the shadow rule.
        Files.delete(feat.resolve("AGENTS.md"));
        Files.writeString(feat.resolve("AGENTS.override.md"), "feat override", StandardCharsets.UTF_8);

        var files = ContextFileDiscovery.discover(feat, null);

        assertThat(files).extracting(f -> f.content())
            .containsExactly("main instructions", "feat override");
    }

    @Test
    void aWorktreeNestedInADeeperSubdirectoryStillShadows() throws Exception {
        var main = temp.resolve("main");
        var mainGit = main.resolve(".git");
        Files.createDirectories(mainGit);
        Files.writeString(mainGit.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
        Files.writeString(main.resolve("AGENTS.md"), "main instructions", StandardCharsets.UTF_8);

        var feat = main.resolve("deep/nested/feat");
        Files.createDirectories(feat);
        var worktrees = mainGit.resolve("worktrees/feat");
        Files.createDirectories(worktrees);
        Files.writeString(worktrees.resolve("HEAD"), "ref: refs/heads/feat\n", StandardCharsets.UTF_8);
        Files.writeString(worktrees.resolve("commondir"), "../../\n", StandardCharsets.UTF_8);
        Files.writeString(feat.resolve(".git"), "gitdir: " + worktrees + "\n", StandardCharsets.UTF_8);
        Files.writeString(feat.resolve("AGENTS.md"), "feat instructions", StandardCharsets.UTF_8);

        var files = ContextFileDiscovery.discover(feat, null);

        // main/AGENTS.md is skipped; only the worktree's own file survives.
        assertThat(files).extracting(f -> f.content()).containsExactly("feat instructions");
    }

    @Test
    void aSiblingWorktreeDoesNotShadow() throws Exception {
        var main = temp.resolve("main");
        var mainGit = main.resolve(".git");
        Files.createDirectories(mainGit);
        Files.writeString(mainGit.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
        Files.writeString(main.resolve("AGENTS.md"), "main instructions", StandardCharsets.UTF_8);

        // `git worktree add ../feat`: the worktree is a sibling, so the main repo is not its
        // ancestor and the worktree root does not start with the main repo root.
        var feat = temp.resolve("feat");
        Files.createDirectories(feat);
        var worktrees = mainGit.resolve("worktrees/feat");
        Files.createDirectories(worktrees);
        Files.writeString(worktrees.resolve("HEAD"), "ref: refs/heads/feat\n", StandardCharsets.UTF_8);
        Files.writeString(worktrees.resolve("commondir"), "../../\n", StandardCharsets.UTF_8);
        Files.writeString(feat.resolve(".git"), "gitdir: " + worktrees + "\n", StandardCharsets.UTF_8);
        Files.writeString(feat.resolve("AGENTS.md"), "feat instructions", StandardCharsets.UTF_8);

        var files = ContextFileDiscovery.discover(feat, null);

        // The walk never reaches main/, so only the worktree's file loads — no shadow involved.
        assertThat(files).extracting(f -> f.content()).containsExactly("feat instructions");
    }

    @Test
    void aBareLayoutIsNotTreatedAsAMainWorktree() throws Exception {
        // proj/.bare is the common git dir; proj/main is the checked-out worktree. dirname of
        // the common dir (proj) holds no .git of its own, so the check rejects it. proj/AGENTS.md
        // exists precisely so that a missing check would shadow it and be caught.
        var proj = temp.resolve("proj");
        var bare = proj.resolve(".bare");
        Files.createDirectories(bare);
        Files.writeString(bare.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
        Files.writeString(proj.resolve("AGENTS.md"), "proj instructions", StandardCharsets.UTF_8);

        var main = proj.resolve("main");
        Files.createDirectories(main);
        Files.writeString(main.resolve(".git"), "gitdir: " + bare + "\n", StandardCharsets.UTF_8);
        Files.writeString(main.resolve("AGENTS.md"), "main instructions", StandardCharsets.UTF_8);

        var files = ContextFileDiscovery.discover(main, null);

        assertThat(files).extracting(f -> f.content())
            .containsExactly("proj instructions", "main instructions");
    }
}
