package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.ExtensionExec;

import org.junit.jupiter.api.Test;

/** docs/52 §2.8：auto-commit-on-exit 验收——commit message 生成、git 命令序列、通知。 */
class AutoCommitOnExitExtensionTest {

    private static Entry assistant(String text) {
        return new Entry.Message("a", 0, null, Instant.ofEpochMilli(1),
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text))), false);
    }

    private static ExtensionExec.ExecResult result(String stdout, int code) {
        return new ExtensionExec.ExecResult(stdout, "", code, false, false);
    }

    @Test
    void commitMessageGeneration() {
        assertThat(AutoCommitOnExitExtension.commitMessage("Fixed the parser bug\nmore details"))
            .isEqualTo("[pi] Fixed the parser bug");
        assertThat(AutoCommitOnExitExtension.commitMessage("")).isEqualTo("[pi] Work in progress");
        assertThat(AutoCommitOnExitExtension.commitMessage(null))
            .isEqualTo("[pi] Work in progress");
        var longText = "x".repeat(60);
        assertThat(AutoCommitOnExitExtension.commitMessage(longText))
            .isEqualTo("[pi] " + "x".repeat(50) + "...");
    }

    @Test
    void noChangesOrNonGitRepoDoesNothing() {
        var w = new W3Context();
        var log = new ArrayList<String>();
        var ext = new AutoCommitOnExitExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            log.add(cmd + " " + String.join(" ", args));
            return result("", 0); // status 空
        });
        w.entries = List.of(assistant("change"));

        ext.onShutdown(w.ctx);

        assertThat(log).containsExactly("git status --porcelain");
    }

    @Test
    void commitsWithGeneratedMessageAndNotifies() {
        var w = new W3Context();
        var log = new ArrayList<String>();
        var ext = new AutoCommitOnExitExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            log.add(cmd + " " + String.join(" ", args));
            if (args.equals(List.of("status", "--porcelain"))) {
                return result(" M file.txt", 0);
            }
            return result("", 0);
        });
        w.entries = List.of(assistant("Refactored the cache layer\nbody"));

        ext.onShutdown(w.ctx);

        assertThat(log).containsExactly(
            "git status --porcelain", "git add -A", "git commit -m [pi] Refactored the cache layer");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Auto-committed: [pi] Refactored the cache layer");
    }

    @Test
    void noNotifyWhenCommitFails() {
        var w = new W3Context();
        var ext = new AutoCommitOnExitExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            if (args.equals(List.of("status", "--porcelain"))) {
                return result(" M file", 0);
            }
            if (args.get(0).equals("commit")) {
                return result("", 1);
            }
            return result("", 0);
        });
        w.entries = List.of(assistant("work"));

        ext.onShutdown(w.ctx);

        assertThat(w.ui.notices()).isEmpty();
    }
}
