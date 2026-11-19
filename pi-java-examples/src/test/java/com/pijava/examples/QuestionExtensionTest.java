package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;

import org.junit.jupiter.api.Test;

/** docs/27 W6：question 验收——两条错误臂、结果三臂、组件交互与文本级渲染器。 */
class QuestionExtensionTest {

    private static final List<QuestionExtension.Option> OPTIONS = List.of(
        QuestionExtension.Option.plain("Alpha", null),
        QuestionExtension.Option.plain("Beta", "second option"));

    private static KeyStroke key(String name) {
        return KeyStroke.of(name, false, false, false);
    }

    private static QuestionExtension.QuestionComponent component(
            AtomicReference<QuestionExtension.Answer> sink) {
        return new QuestionExtension.QuestionComponent("Pick one", 
            QuestionExtension.displayOptions(OPTIONS), (slot, text) -> text, sink::set);
    }

    private static String text(com.pijava.agent.tool.ToolResult<Map<String, Object>> result) {
        return ((com.pijava.ai.message.ContentBlock.TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static List<String> optionsOf(Map<String, Object> details) {
        return (List<String>) details.get("options");
    }

    @Test
    void nonInteractiveModeReturnsError() {
        var w = new W3Context();
        w.mode = "print";
        var tool = new QuestionExtension.QuestionTool(w.ctx);

        var result = tool.execute("t", Map.of("question", "Q?",
            "options", List.of(Map.of("label", "A"))), null, null, null);

        assertThat(text(result)).isEqualTo(QuestionExtension.NO_UI_ERROR);
        assertThat(result.details()).containsEntry("question", "Q?")
            .containsEntry("answer", null);
        assertThat(optionsOf(result.details())).containsExactly("A");
    }

    @Test
    void emptyOptionsReturnsError() {
        var w = new W3Context();
        var tool = new QuestionExtension.QuestionTool(w.ctx);

        var result = tool.execute("t", Map.of("question", "Q?"), null, null, null);

        assertThat(text(result)).isEqualTo(QuestionExtension.NO_OPTIONS_ERROR);
        assertThat((List<?>) result.details().get("options")).isEmpty();
    }

    @Test
    void cancelledWhenComponentClosesWithoutAnswer() {
        var w = new W3Context();
        var tool = new QuestionExtension.QuestionTool(w.ctx);

        var result = tool.execute("t", Map.of("question", "Q?",
            "options", List.of(Map.of("label", "A"), Map.of("label", "B"))), null, null, null);

        assertThat(text(result)).isEqualTo(QuestionExtension.CANCELLED);
        assertThat(result.details()).containsEntry("answer", null);
        assertThat(optionsOf(result.details())).containsExactly("A", "B");
    }

    @Test
    void resultArms() {
        var selected = QuestionExtension.result(
            new QuestionExtension.Answer("Beta", false, 2), "Q?", List.of("Alpha", "Beta"));
        assertThat(text(selected)).isEqualTo("User selected: 2. Beta");
        assertThat(selected.details()).containsEntry("answer", "Beta")
            .containsEntry("wasCustom", false);

        var custom = QuestionExtension.result(
            new QuestionExtension.Answer("free text", true, null), "Q?", List.of("Alpha"));
        assertThat(text(custom)).isEqualTo("User wrote: free text");
        assertThat(custom.details()).containsEntry("wasCustom", true);

        var cancelled = QuestionExtension.result(null, "Q?", List.of("Alpha"));
        assertThat(text(cancelled)).isEqualTo(QuestionExtension.CANCELLED);
        assertThat(cancelled.details()).containsEntry("answer", null);
    }

    @Test
    void schemaCarriesQuestionAndOptions() {
        var schema = QuestionExtension.inputSchema();
        assertThat(schema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("question", "options");
    }

    @Test
    void displayOptionsAppendOtherLabel() {
        var all = QuestionExtension.displayOptions(OPTIONS);
        assertThat(all).hasSize(3);
        assertThat(all.get(2).label()).isEqualTo(QuestionExtension.OTHER_LABEL);
        assertThat(all.get(2).isOther()).isTrue();
    }

    @Test
    void enterOnPlainOptionAnswers() {
        var sink = new AtomicReference<QuestionExtension.Answer>();
        var component = component(sink);

        component.handleInput(key("enter"));

        assertThat(sink.get()).isEqualTo(new QuestionExtension.Answer("Alpha", false, 1));
    }

    @Test
    void arrowsClampAndOtherEntersEditMode() {
        var sink = new AtomicReference<QuestionExtension.Answer>();
        var component = component(sink);

        component.handleInput(key("down"));
        component.handleInput(key("down"));
        component.handleInput(key("down"));
        assertThat(component.optionIndex()).isEqualTo(2);

        component.handleInput(key("up"));
        assertThat(component.optionIndex()).isEqualTo(1);

        component.handleInput(key("down"));
        component.handleInput(key("enter"));
        assertThat(component.editMode()).isTrue();
        assertThat(sink.get()).isNull();
    }

    @Test
    void editorSubmitSendsCustomAnswer() {
        var sink = new AtomicReference<QuestionExtension.Answer>();
        var component = component(sink);
        component.handleInput(key("down"));
        component.handleInput(key("down"));
        component.handleInput(key("enter"));

        "hello".chars().forEach(c -> component.handleInput(
            KeyStroke.of(String.valueOf((char) c), false, false, false)));
        component.handleInput(key("enter"));

        assertThat(sink.get()).isEqualTo(new QuestionExtension.Answer("hello", true, null));
    }

    @Test
    void emptyEditorSubmitReturnsToList() {
        var sink = new AtomicReference<QuestionExtension.Answer>();
        var component = component(sink);
        component.handleInput(key("down"));
        component.handleInput(key("down"));
        component.handleInput(key("enter"));

        component.handleInput(key("enter"));

        assertThat(component.editMode()).isFalse();
        assertThat(component.editor().getText()).isEmpty();
        assertThat(sink.get()).isNull();
    }

    @Test
    void escapeLeavesEditModeThenCancels() {
        var sink = new AtomicReference<QuestionExtension.Answer>();
        var component = component(sink);
        component.handleInput(key("down"));
        component.handleInput(key("down"));
        component.handleInput(key("enter"));
        component.handleInput(key("esc"));
        assertThat(component.editMode()).isFalse();

        component.handleInput(key("esc"));
        assertThat(sink.get()).isNull();
    }

    @Test
    void renderShowsNumberedOptionsAndHints() {
        var component = component(new AtomicReference<>());

        var lines = component.render(30);
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("> 1. Alpha"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("2. Beta"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
            .contains("     second option"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
            .contains("3. " + QuestionExtension.OTHER_LABEL));
        assertThat(lines).anySatisfy(line -> assertThat(line)
            .contains("↑↓ navigate • Enter to select • Esc to cancel"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("Pick one"));
    }

    @Test
    void rendererShowsArgsAndResultArms() {
        var renderer = new QuestionExtension.QuestionRenderer();

        var call = renderer.renderCall(QuestionExtension.TOOL_NAME,
            Map.of("question", "Q?", "options",
                List.of(Map.of("label", "A"), Map.of("label", "B"))), false);
        assertThat(String.join("\n", call)).isEqualTo(
            "question Q?\n  Options: 1. A, 2. B, 3. Type something.");

        assertThat(renderer.renderResult(QuestionExtension.TOOL_NAME,
            "User selected: 2. Beta", false, false)).containsExactly("✓ 2. Beta");
        assertThat(renderer.renderResult(QuestionExtension.TOOL_NAME,
            "User wrote: hi", false, false)).containsExactly("✓ (wrote) hi");
        assertThat(renderer.renderResult(QuestionExtension.TOOL_NAME,
            QuestionExtension.CANCELLED, false, false)).containsExactly("Cancelled");
    }
}
