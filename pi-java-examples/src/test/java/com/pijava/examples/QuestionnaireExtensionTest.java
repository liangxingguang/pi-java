package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;

import org.junit.jupiter.api.Test;

/** docs/27 W6：questionnaire 验收——缺省规范化、页签/提交页、手写臂与结果行。 */
class QuestionnaireExtensionTest {

    private static KeyStroke key(String name) {
        return KeyStroke.of(name, false, false, false);
    }

    private static KeyStroke shiftTab() {
        return KeyStroke.of("tab", false, false, true);
    }

    private static Map<String, Object> question(String id, String label, boolean allowOther) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        if (label != null) {
            map.put("label", label);
        }
        map.put("prompt", "Prompt of " + id);
        map.put("options", List.of(
            Map.of("value", id + "-1", "label", "First"),
            Map.of("value", id + "-2", "label", "Second")));
        if (!allowOther) {
            map.put("allowOther", false);
        }
        return map;
    }

    private static String text(com.pijava.agent.tool.ToolResult<
            QuestionnaireExtension.QuestionnaireResult> result) {
        return ((com.pijava.ai.message.ContentBlock.TextContent) result.content().get(0)).text();
    }

    @Test
    void normalizeAppliesPiDefaults() {
        var questions = QuestionnaireExtension.normalize(List.of(
            question("a", null, true), question("b", "Scope", false)));

        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).label()).isEqualTo("Q1");
        assertThat(questions.get(0).allowOther()).isTrue();
        assertThat(questions.get(1).label()).isEqualTo("Scope");
        assertThat(questions.get(1).allowOther()).isFalse();
        assertThat(questions.get(0).options()).extracting(
            QuestionnaireExtension.QuestionOption::value).containsExactly("a-1", "a-2");
    }

    @Test
    void currentOptionsRespectsAllowOther() {
        var withOther = QuestionnaireExtension.normalize(List.of(question("a", null, true)));
        assertThat(QuestionnaireExtension.currentOptions(withOther.get(0))).hasSize(3);
        assertThat(QuestionnaireExtension.currentOptions(withOther.get(0)).get(2).isOther())
            .isTrue();

        var withoutOther = QuestionnaireExtension.normalize(List.of(question("a", null, false)));
        assertThat(QuestionnaireExtension.currentOptions(withoutOther.get(0))).hasSize(2);
        assertThat(QuestionnaireExtension.currentOptions(null)).isEmpty();
    }

    @Test
    void errorArms() {
        var w = new W3Context();
        w.mode = "print";
        var tool = new QuestionnaireExtension.QuestionnaireTool(w.ctx);
        var noUi = tool.execute("t", Map.of("questions", List.of(question("a", null, true))),
            null, null, null);
        assertThat(text(noUi)).isEqualTo(QuestionnaireExtension.NO_UI_ERROR);
        assertThat(noUi.details().cancelled()).isTrue();

        w.mode = "tui";
        var empty = tool.execute("t", Map.of(), null, null, null);
        assertThat(text(empty)).isEqualTo(QuestionnaireExtension.NO_QUESTIONS_ERROR);
        assertThat(empty.details().answers()).isEmpty();
    }

    @Test
    void cancelledWhenComponentClosesWithoutSubmit() {
        var w = new W3Context();
        var tool = new QuestionnaireExtension.QuestionnaireTool(w.ctx);

        var result = tool.execute("t",
            Map.of("questions", List.of(question("a", null, true))), null, null, null);

        assertThat(text(result)).isEqualTo(QuestionnaireExtension.CANCELLED);
        assertThat(result.details().cancelled()).isTrue();
    }

    @Test
    void singleQuestionSubmitsOnSelection() {
        var questions = QuestionnaireExtension.normalize(
            List.of(question("a", null, false)));
        var sink = new AtomicReference<QuestionnaireExtension.QuestionnaireResult>();
        var component = new QuestionnaireComponent(questions, (slot, text) -> text, sink::set);

        component.handleInput(key("down"));
        component.handleInput(key("enter"));

        var result = sink.get();
        assertThat(result.cancelled()).isFalse();
        assertThat(result.answers()).containsExactly(
            new QuestionnaireExtension.Answer("a", "a-2", "Second", false, 2));
    }

    @Test
    void multiQuestionAdvancesThroughTabsToSubmit() {
        var questions = QuestionnaireExtension.normalize(
            List.of(question("a", null, false), question("b", "Priority", false)));
        var sink = new AtomicReference<QuestionnaireExtension.QuestionnaireResult>();
        var component = new QuestionnaireComponent(questions, (slot, text) -> text, sink::set);

        assertThat(component.currentTab()).isZero();
        component.handleInput(key("enter"));
        assertThat(component.currentTab()).isEqualTo(1);

        component.handleInput(key("enter"));
        assertThat(component.currentTab()).isEqualTo(2);
        assertThat(component.allAnswered()).isTrue();

        component.handleInput(key("enter"));
        var result = sink.get();
        assertThat(result.cancelled()).isFalse();
        assertThat(result.answers()).extracting(QuestionnaireExtension.Answer::id)
            .containsExactly("a", "b");
    }

    @Test
    void tabsWrapBothWays() {
        var questions = QuestionnaireExtension.normalize(
            List.of(question("a", null, false), question("b", null, false)));
        var component = new QuestionnaireComponent(questions, (slot, text) -> text, r -> { });

        component.handleInput(key("tab"));
        assertThat(component.currentTab()).isEqualTo(1);
        component.handleInput(key("tab"));
        component.handleInput(key("tab"));
        assertThat(component.currentTab()).isZero();
        component.handleInput(shiftTab());
        assertThat(component.currentTab()).isEqualTo(2);
        component.handleInput(key("left"));
        assertThat(component.currentTab()).isEqualTo(1);
        component.handleInput(key("right"));
        assertThat(component.currentTab()).isEqualTo(2);
    }

    @Test
    void submitTabReportsUnanswered() {
        var questions = QuestionnaireExtension.normalize(
            List.of(question("a", null, false), question("b", "Priority", false)));
        var component = new QuestionnaireComponent(questions, (slot, text) -> text, r -> { });

        component.handleInput(key("tab"));
        component.handleInput(key("tab"));
        assertThat(component.currentTab()).isEqualTo(2);
        assertThat(component.allAnswered()).isFalse();
        assertThat(component.render(40)).anySatisfy(
            line -> assertThat(line).contains("Unanswered: Q1, Priority"));
    }

    @Test
    void otherOptionCollectsCustomAnswer() {
        var questions = QuestionnaireExtension.normalize(List.of(question("a", null, true)));
        var sink = new AtomicReference<QuestionnaireExtension.QuestionnaireResult>();
        var component = new QuestionnaireComponent(questions, (slot, text) -> text, sink::set);

        component.handleInput(key("down"));
        component.handleInput(key("down"));
        component.handleInput(key("enter"));
        assertThat(component.inputMode()).isTrue();

        "typed".chars().forEach(c -> component.handleInput(
            KeyStroke.of(String.valueOf((char) c), false, false, false)));
        component.handleInput(key("enter"));

        assertThat(sink.get().answers()).containsExactly(
            new QuestionnaireExtension.Answer("a", "typed", "typed", true, null));
    }

    @Test
    void emptyCustomAnswerBecomesNoResponse() {
        var questions = QuestionnaireExtension.normalize(List.of(question("a", null, true)));
        var sink = new AtomicReference<QuestionnaireExtension.QuestionnaireResult>();
        var component = new QuestionnaireComponent(questions, (slot, text) -> text, sink::set);

        component.handleInput(key("down"));
        component.handleInput(key("down"));
        component.handleInput(key("enter"));
        component.handleInput(key("enter"));

        assertThat(sink.get().answers()).containsExactly(new QuestionnaireExtension.Answer(
            "a", QuestionnaireExtension.NO_RESPONSE, QuestionnaireExtension.NO_RESPONSE,
            true, null));
    }

    @Test
    void escapeInInputModeReturnsAndEscapeInListCancels() {
        var questions = QuestionnaireExtension.normalize(List.of(question("a", null, true)));
        var sink = new AtomicReference<QuestionnaireExtension.QuestionnaireResult>();
        var component = new QuestionnaireComponent(questions, (slot, text) -> text, sink::set);

        component.handleInput(key("down"));
        component.handleInput(key("down"));
        component.handleInput(key("enter"));
        component.handleInput(key("esc"));
        assertThat(component.inputMode()).isFalse();
        assertThat(sink.get()).isNull();

        component.handleInput(key("esc"));
        assertThat(sink.get().cancelled()).isTrue();
    }

    @Test
    void resultLinesMatchPiTemplates() {
        var questions = QuestionnaireExtension.normalize(
            List.of(question("a", "Scope", false), question("b", "Priority", false)));
        var answers = List.of(
            new QuestionnaireExtension.Answer("a", "a-1", "First", false, 1),
            new QuestionnaireExtension.Answer("b", "own", "own", true, null));
        var result = new QuestionnaireExtension.QuestionnaireResult(questions, answers, false);

        var submitted = QuestionnaireExtension.result(result, questions);
        assertThat(text(submitted))
            .isEqualTo("Scope: user selected: 1. First\nPriority: user wrote: own");
        assertThat(submitted.details().answers()).hasSize(2);
    }

    @Test
    void renderShowsTabsAndPrompt() {
        var questions = QuestionnaireExtension.normalize(
            List.of(question("a", null, false), question("b", "Priority", false)));
        var component = new QuestionnaireComponent(questions, (slot, text) -> text, r -> { });

        var lines = component.render(40);
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("Q1").contains("Priority")
            .contains("Submit"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("Prompt of a"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
            .contains("Tab/←→ navigate • ↑↓ select • Enter confirm • Esc cancel"));
    }

    @Test
    void rendererFormatsCallAndResult() {
        var renderer = new QuestionnaireExtension.QuestionnaireRenderer();

        assertThat(renderer.renderCall(QuestionnaireExtension.TOOL_NAME,
            Map.of("questions", List.of(question("a", "Scope", true))), false))
            .containsExactly("questionnaire 1 question (Scope)");
        assertThat(renderer.renderCall(QuestionnaireExtension.TOOL_NAME,
            Map.of("questions", List.of()), false))
            .containsExactly("questionnaire 0 questions");

        assertThat(renderer.renderResult(QuestionnaireExtension.TOOL_NAME,
            "Scope: user selected: 1. First", false, false))
            .containsExactly("✓ Scope: user selected: 1. First");
        assertThat(renderer.renderResult(QuestionnaireExtension.TOOL_NAME,
            QuestionnaireExtension.CANCELLED, false, false)).containsExactly("Cancelled");
    }
}
