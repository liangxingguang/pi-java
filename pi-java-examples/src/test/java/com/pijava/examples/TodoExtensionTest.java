package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;

/** docs/52 §2.2：todo 验收——状态机各臂文本、details 持久化、分支重建、渲染降级。 */
class TodoExtensionTest {

    @Test
    void addListToggleClearArms() {
        var state = new TodoExtension.TodoState();

        var added = state.execute("add", "first task", null);
        assertThat(added.get("content")).isEqualTo("Added todo #1: first task");
        assertThat(added.get("nextId")).isEqualTo(2);
        assertThat((List<?>) added.get("todos")).hasSize(1);

        state.execute("add", "second task", null);
        var list = state.execute("list", null, null);
        assertThat(list.get("content")).isEqualTo("[ ] #1: first task\n[ ] #2: second task");

        var toggled = state.execute("toggle", null, "1");
        assertThat(toggled.get("content")).isEqualTo("Todo #1 completed");
        assertThat(state.execute("list", null, null).get("content"))
            .isEqualTo("[x] #1: first task\n[ ] #2: second task");

        var cleared = state.execute("clear", null, null);
        assertThat(cleared.get("content")).isEqualTo("Cleared 2 todos");
        assertThat(state.execute("list", null, null).get("content")).isEqualTo("No todos");
    }

    @Test
    void errorArmsAreVerbatim() {
        var state = new TodoExtension.TodoState();
        assertThat(state.execute("add", "  ", null).get("content"))
            .isEqualTo("Error: text required for add");
        assertThat(state.execute("toggle", null, null).get("content"))
            .isEqualTo("Error: id required for toggle");
        assertThat(state.execute("toggle", null, "99").get("content"))
            .isEqualTo("Todo #99 not found");
        assertThat(state.execute("bogus", null, null).get("content"))
            .isEqualTo("Unknown action: bogus");
        assertThat(state.execute("add", "  ", null).get("error")).isEqualTo("text required");
        assertThat(state.execute("toggle", null, "99").get("error")).isEqualTo("#99 not found");
    }

    @Test
    void rebuildFromBranchToolResults() {
        var w = new W3Context();
        var state = new TodoExtension.TodoState();
        var details = Map.of("action", "add", "nextId", 3,
            "todos", List.of(
                Map.of("step", 1, "text", "kept", "completed", true),
                Map.of("step", 2, "text", "pending", "completed", false)));
        var toolResult = new Message.ToolResultMessage("c1", "todo",
            List.of(new ContentBlock.TextContent("r")), details, null, List.of(), false,
            Instant.ofEpochMilli(1));
        w.branch = List.of(new Entry.Message("e1", 0, null, Instant.ofEpochMilli(1),
            toolResult, false));

        TodoExtension.rebuild(w.ctx, state);

        assertThat(state.todos).hasSize(2);
        assertThat(state.todos.get(0).text).isEqualTo("kept");
        assertThat(state.todos.get(0).done).isTrue();
        assertThat(state.nextId).isEqualTo(3);
    }

    @Test
    void renderResultDefaultArmTextualErrorAndEmpty() {
        var renderer = new TodoExtension.TextToolRenderer();
        assertThat(renderer.renderResult("todo", "boom", true, false))
            .containsExactly("Error: boom");
        assertThat(renderer.renderResult("todo", "", true, false)).isEmpty();
        assertThat(renderer.renderResult("todo", "Added todo #1: x", false, false))
            .containsExactly("✓ Added todo #1: x");
    }

    @Test
    void todosCommandRequiresTui() {
        var w = new W3Context();
        w.mode = "print";
        new TodoExtension().register(w.ctx);

        w.command("todos", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("/todos requires interactive mode");
        assertThat(w.ui.notices().get(0).type()).isEqualTo(NotifyType.ERROR);
    }
}
