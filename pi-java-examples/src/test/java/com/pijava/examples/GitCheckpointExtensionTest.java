package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.extension.ExtensionExec;

import org.junit.jupiter.api.Test;

/** docs/52 §2.9：git-checkpoint 验收——四事件编排、map 生命周期、恢复分支。 */
class GitCheckpointExtensionTest {

    private static ExtensionExec.ExecResult result(String stdout, int code) {
        return new ExtensionExec.ExecResult(stdout, "", code, false, false);
    }

    private static Entry entry(String id) {
        return new Entry.Custom(id, 0, null, Instant.ofEpochMilli(1), "x", java.util.Map.of());
    }

    @Test
    void checkpointLifecycle() {
        var w = new W3Context();
        var log = new ArrayList<String>();
        var ext = new GitCheckpointExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            log.add(cmd + " " + String.join(" ", args));
            return result("stash-ref-1\n", 0);
        });
        w.leafEntry = entry("e1");

        ext.onToolResult(w.ctx);
        ext.onTurnStart();

        assertThat(ext.checkpoints()).containsEntry("e1", "stash-ref-1");
        assertThat(log).containsExactly("git stash create");
    }

    @Test
    void noCheckpointWithoutLeafOrEmptyRef() {
        var w = new W3Context();
        var ext = new GitCheckpointExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> result("  \n", 0));

        ext.onToolResult(w.ctx); // 无 leaf
        ext.onTurnStart();
        assertThat(ext.checkpoints()).isEmpty();

        w.leafEntry = entry("e2");
        ext.onToolResult(w.ctx);
        ext.onTurnStart(); // 空 ref
        assertThat(ext.checkpoints()).isEmpty();
    }

    @Test
    void forkRestoresOnYes() {
        var w = new W3Context(new ExampleFixtures.ScriptedUi()
            .answer("Yes, restore code to that point"));
        var log = new ArrayList<String>();
        var ext = new GitCheckpointExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            log.add(cmd + " " + String.join(" ", args));
            return result("ref-9", 0);
        });
        ext.checkpoints().put("e1", "ref-9");

        ext.onBeforeFork("e1", w.ctx);

        assertThat(log).containsExactly("git stash apply ref-9");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Code restored to checkpoint");
    }

    @Test
    void forkKeepsCodeOnNoOrMissingRef() {
        var w = new W3Context(new ExampleFixtures.ScriptedUi()
            .answer("No, keep current code"));
        var log = new ArrayList<String>();
        var ext = new GitCheckpointExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            log.add(cmd + " " + String.join(" ", args));
            return result("", 0);
        });
        ext.checkpoints().put("e1", "ref-9");

        ext.onBeforeFork("e1", w.ctx); // No
        ext.onBeforeFork("missing", w.ctx); // 无 ref
        assertThat(log).isEmpty();
        assertThat(w.ui.notices()).isEmpty();
    }

    @Test
    void nonInteractiveForkDoesNothing() {
        var w = new W3Context();
        w.mode = "print";
        var log = new ArrayList<String>();
        var ext = new GitCheckpointExtension();
        ext.register(w.ctx);
        ext.git((cmd, args) -> {
            log.add(cmd + " " + String.join(" ", args));
            return result("", 0);
        });
        ext.checkpoints().put("e1", "ref");

        ext.onBeforeFork("e1", w.ctx);

        assertThat(log).isEmpty();
    }
}
