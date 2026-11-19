package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.extension.ExtensionExec;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/55 §2.4：git-merge-and-resolve 验收——区间/报告纯函数、冲突状态机、git 序列、followUp。 */
class GitMergeAndResolveExtensionTest {

    private static ExtensionExec.ExecResult r(String stdout, int code) {
        return new ExtensionExec.ExecResult(stdout, "", code, false, false);
    }

    private static GitMergeAndResolveExtension.ConflictBlock block(
            String f, int s, int sep, int e) {
        return new GitMergeAndResolveExtension.ConflictBlock(f, s, sep, e);
    }

    @Test
    void formatRangeThreeBranches() {
        assertThat(GitMergeAndResolveExtension.formatRange(5, 3)).isEqualTo("empty");
        assertThat(GitMergeAndResolveExtension.formatRange(5, 5)).isEqualTo("5");
        assertThat(GitMergeAndResolveExtension.formatRange(5, 9)).isEqualTo("5-9");
    }

    @Test
    void formatConflictsVerbatim() {
        var report = GitMergeAndResolveExtension.formatConflicts("origin/main", List.of(
            block("a.txt", 3, 5, 8)));
        assertThat(report).isEqualTo(String.join("\n",
            "Merged origin/main with conflicts:",
            "",
            "  a.txt:3-8 (ours 4, theirs 6-7)",
            "",
            "Resolve these conflicts."));
    }

    @Test
    void parseConflictBlocksStateMachine() {
        var lines = List.of(
            "context", "<<<<<<< HEAD", "ours 1", "=======", "theirs 1", ">>>>>>> branch",
            "<<<<<<< HEAD", "ours 2", "=======", "theirs 2", ">>>>>>> branch");
        var blocks = GitMergeAndResolveExtension.parseConflictBlocks("a.txt", lines);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isEqualTo(block("a.txt", 2, 4, 6));
        assertThat(blocks.get(1)).isEqualTo(block("a.txt", 7, 9, 11));
    }

    @Test
    void notGitRepoReturnsImmediately() {
        var w = new W3Context();
        var log = new ArrayList<String>();
        var ext = new GitMergeAndResolveExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            log.add(cmd + " " + String.join(" ", args));
            return r("", 128); // rev-parse --git-dir 失败
        });

        ext.onAgentEnd(w.ctx);

        assertThat(log).containsExactly("git rev-parse --git-dir");
        assertThat(w.sentMessages).isEmpty();
    }

    @Test
    void inProgressMergeResolvesConflicts() {
        var w = new W3Context();
        var log = new ArrayList<String>();
        var ext = new GitMergeAndResolveExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            log.add(cmd + " " + String.join(" ", args));
            var key = String.join(" ", args);
            if (key.equals("rev-parse --git-dir")) return r(".git", 0);
            if (key.equals("rev-parse MERGE_HEAD")) return r("abc", 0); // 在 merge 中
            if (key.equals("diff --name-only --diff-filter=U")) return r("a.txt", 0);
            return r("", 0);
        });
        ext.fileReader(path -> List.of("<<<<<<< HEAD", "ours", "=======", "theirs", ">>>>>>> b"));

        ext.onAgentEnd(w.ctx);

        assertThat(log).containsExactly("git rev-parse --git-dir", "git rev-parse MERGE_HEAD",
            "git diff --name-only --diff-filter=U");
        assertThat(w.sentMessages).hasSize(1);
        assertThat(w.sentMessages.get(0)).contains("Merged MERGE_HEAD with conflicts:")
            .contains("Resolve these conflicts.");
    }

    @Test
    void cleanWorktreeBlocksNewMerge() {
        var w = new W3Context();
        var log = new ArrayList<String>();
        var ext = new GitMergeAndResolveExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            log.add(cmd + " " + String.join(" ", args));
            var key = String.join(" ", args);
            if (key.equals("rev-parse --git-dir")) return r(".git", 0);
            if (key.equals("rev-parse MERGE_HEAD")) return r("", 128); // 不在 merge
            if (key.equals("status --porcelain")) return r(" M dirty", 0); // 工作树不干净
            return r("", 0);
        });

        ext.onAgentEnd(w.ctx);

        assertThat(log).containsExactly("git rev-parse --git-dir", "git rev-parse MERGE_HEAD",
            "git status --porcelain");
        assertThat(w.sentMessages).isEmpty();
    }

    @Test
    void newMergeFetchFailureNotifiesWarning() {
        var w = new W3Context();
        var ext = new GitMergeAndResolveExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            var key = String.join(" ", args);
            if (key.equals("rev-parse --git-dir")) return r(".git", 0);
            if (key.equals("rev-parse MERGE_HEAD")) return r("", 128);
            if (key.equals("status --porcelain")) return r("", 0);
            if (key.startsWith("rev-parse --abbrev-ref")) return r("origin/main", 0);
            if (key.equals("fetch origin")) return new ExtensionExec.ExecResult("", "network down", 1, false, false);
            return r("", 0);
        });

        ext.onAgentEnd(w.ctx);

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("git-merge-and-resolve: fetching origin, merging origin/main")
            .contains("git-merge-and-resolve: fetch failed: network down");
    }

    @Test
    void eventRegistration() {
        var w = new W3Context();
        new GitMergeAndResolveExtension().register(w.ctx);
        // agent_end 登记后 fire 不抛（默认 git 返回空→提前返回）
        w.fire(ExtensionEventSpec.AGENT_END, null);
    }
}
