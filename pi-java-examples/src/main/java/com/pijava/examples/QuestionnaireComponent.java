package com.pijava.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import com.pijava.coding.agent.extension.ExtensionComponent;

/**
 * pi {@code examples/extensions/questionnaire.ts:110-394} 的问卷组件
 * （docs/27 W6）——页签条 ＋ 选项列表 ＋ 提交页 ＋ 内联编辑器。
 *
 * <p>与 {@link QuestionnaireExtension} 拆成两个文件只为守住「文件 ≤ 500 行」的
 * 仓库纪律（pi 侧是同一 {@code registerTool} 内的闭包）；两者职责与 pi 一一对应：
 * 本类持有交互状态（{@code currentTab}/{@code optionIndex}/{@code inputMode}/
 * {@code answers}），扩展类持有 schema、缺省规范化与结果投影。</p>
 *
 * <p>窄差：文本级渲染（无软换行、无 {@code theme.bg}，见 {@link ExampleEditor}）。</p>
 */
final class QuestionnaireComponent implements ExtensionComponent {

    private final List<QuestionnaireExtension.Question> questions;
    private final BiFunction<String, String, String> colorize;
    private final Consumer<QuestionnaireExtension.QuestionnaireResult> done;
    private final ExampleEditor editor;
    private final Map<String, QuestionnaireExtension.Answer> answers = new LinkedHashMap<>();
    private final boolean isMulti;
    private final int totalTabs;
    private int currentTab;
    private int optionIndex;
    private boolean inputMode;
    private String inputQuestionId;

    QuestionnaireComponent(List<QuestionnaireExtension.Question> questions,
                           BiFunction<String, String, String> colorize,
                           Consumer<QuestionnaireExtension.QuestionnaireResult> done) {
        this.questions = questions;
        this.colorize = colorize == null ? (slot, text) -> text : colorize;
        this.done = done;
        this.editor = new ExampleEditor(colorize);
        this.isMulti = questions.size() > 1;
        this.totalTabs = questions.size() + 1;
    }

    /** 当前页（{@code questions.size()} 即提交页）。 */
    int currentTab() {
        return currentTab;
    }

    /** 当前光标（测试探针）。 */
    int optionIndex() {
        return optionIndex;
    }

    /** 是否在输入态（测试探针）。 */
    boolean inputMode() {
        return inputMode;
    }

    /** 已答表（测试探针）。 */
    Map<String, QuestionnaireExtension.Answer> answers() {
        return answers;
    }

    /** 内联编辑器（测试探针）。 */
    ExampleEditor editor() {
        return editor;
    }

    /** pi L142-144：当前问题（提交页/越界 ⇒ null）。 */
    QuestionnaireExtension.Question currentQuestion() {
        return currentTab < questions.size() ? questions.get(currentTab) : null;
    }

    /** pi L156-158：是否全答。 */
    boolean allAnswered() {
        return questions.stream().allMatch(q -> answers.containsKey(q.id()));
    }

    /** pi L138-140：提交出口。 */
    void submit(boolean cancelled) {
        done.accept(new QuestionnaireExtension.QuestionnaireResult(questions,
            List.copyOf(answers.values()), cancelled));
    }

    /** pi L189-264：输入分派。 */
    @Override
    public void handleInput(KeyStroke key) {
        if (inputMode) {
            if (isEscape(key)) {
                inputMode = false;
                inputQuestionId = null;
                editor.setText("");
                return;
            }
            if (isEnter(key)) {
                submitEditor();
                return;
            }
            editor.handleInput(key);
            return;
        }
        if (isMulti) {
            if (("tab".equals(key.key()) && !key.shift()) || "right".equals(key.key())) {
                currentTab = (currentTab + 1) % totalTabs;
                optionIndex = 0;
                return;
            }
            if (("tab".equals(key.key()) && key.shift()) || "left".equals(key.key())) {
                currentTab = (currentTab - 1 + totalTabs) % totalTabs;
                optionIndex = 0;
                return;
            }
        }
        if (currentTab == questions.size()) {
            if (isEnter(key) && allAnswered()) {
                submit(false);
            } else if (isEscape(key)) {
                submit(true);
            }
            return;
        }
        var opts = QuestionnaireExtension.currentOptions(currentQuestion());
        if ("up".equals(key.key())) {
            optionIndex = Math.max(0, optionIndex - 1);
            return;
        }
        if ("down".equals(key.key())) {
            optionIndex = Math.min(opts.size() - 1, optionIndex + 1);
            return;
        }
        var question = currentQuestion();
        if (isEnter(key) && question != null) {
            var opt = opts.get(optionIndex);
            if (opt.isOther()) {
                inputMode = true;
                inputQuestionId = question.id();
                editor.setText("");
                return;
            }
            answers.put(question.id(), new QuestionnaireExtension.Answer(question.id(),
                opt.option().value(), opt.option().label(), false, optionIndex + 1));
            advanceAfterAnswer();
            return;
        }
        if (isEscape(key)) {
            submit(true);
        }
    }

    /** pi L179-187：编辑器提交（空文本 ⇒ {@code "(no response)"}）。 */
    void submitEditor() {
        if (inputQuestionId == null) {
            return;
        }
        var trimmed = editor.getText().trim();
        var value = trimmed.isEmpty() ? QuestionnaireExtension.NO_RESPONSE : trimmed;
        answers.put(inputQuestionId, new QuestionnaireExtension.Answer(inputQuestionId, value,
            value, true, null));
        inputMode = false;
        inputQuestionId = null;
        editor.setText("");
        advanceAfterAnswer();
    }

    /** pi L160-172：答完推进（单问题直接提交，多问题下一页/提交页）。 */
    private void advanceAfterAnswer() {
        if (!isMulti) {
            submit(false);
            return;
        }
        currentTab = currentTab < questions.size() - 1 ? currentTab + 1 : questions.size();
        optionIndex = 0;
    }

    /** pi L266-385：文本级渲染。 */
    @Override
    public List<String> render(int width) {
        var renderWidth = Math.max(1, width);
        var lines = new ArrayList<String>();
        var border = colorize.apply("accent", ExampleEditor.BORDER_GLYPH.repeat(renderWidth));
        lines.add(border);

        if (isMulti) {
            lines.add(" " + tabBar());
            lines.add("");
        }

        var question = currentQuestion();
        if (inputMode && question != null) {
            lines.add(" " + colorize.apply("text", question.prompt()));
            lines.add("");
            lines.addAll(renderOptions());
            lines.add("");
            lines.add(" " + colorize.apply("muted", "Your answer:"));
            for (var line : editor.render(Math.max(1, renderWidth - 2))) {
                lines.add(" " + line);
            }
            lines.add("");
            lines.add(" " + colorize.apply("dim", "Enter to submit • Esc to cancel"));
        } else if (currentTab == questions.size()) {
            lines.add(" " + colorize.apply("accent", "Ready to submit"));
            lines.add("");
            for (var q : questions) {
                var answer = answers.get(q.id());
                if (answer != null) {
                    var prefix = answer.wasCustom() ? "(wrote) " : "";
                    lines.add(" " + colorize.apply("muted", q.label() + ": ")
                        + colorize.apply("text", prefix + answer.label()));
                }
            }
            lines.add("");
            if (allAnswered()) {
                lines.add(" " + colorize.apply("success", "Press Enter to submit"));
            } else {
                var missing = questions.stream().filter(q -> !answers.containsKey(q.id()))
                    .map(QuestionnaireExtension.Question::label).toList();
                lines.add(" " + colorize.apply("warning",
                    "Unanswered: " + String.join(", ", missing)));
            }
        } else if (question != null) {
            lines.add(" " + colorize.apply("text", question.prompt()));
            lines.add("");
            lines.addAll(renderOptions());
        }

        lines.add("");
        if (!inputMode) {
            lines.add(" " + colorize.apply("dim", isMulti
                ? "Tab/←→ navigate • ↑↓ select • Enter confirm • Esc cancel"
                : "↑↓ navigate • Enter select • Esc cancel"));
        }
        lines.add(border);
        return List.copyOf(lines);
    }

    /** pi L294-314：页签条（多问题才出现）。 */
    private String tabBar() {
        var tabs = new StringBuilder("← ");
        for (int i = 0; i < questions.size(); i++) {
            var q = questions.get(i);
            var answered = answers.containsKey(q.id());
            var box = answered ? "■" : "□";
            var text = " " + box + " " + q.label() + " ";
            tabs.append(i == currentTab
                ? colorize.apply("text", text)
                : colorize.apply(answered ? "success" : "muted", text)).append(' ');
        }
        var canSubmit = allAnswered();
        var submitText = " ✓ Submit ";
        tabs.append(currentTab == questions.size()
            ? colorize.apply("text", submitText)
            : colorize.apply(canSubmit ? "success" : "dim", submitText));
        return tabs.append(" →").toString();
    }

    /** pi L318-332：选项列表。 */
    private List<String> renderOptions() {
        var lines = new ArrayList<String>();
        var opts = QuestionnaireExtension.currentOptions(currentQuestion());
        for (int i = 0; i < opts.size(); i++) {
            var opt = opts.get(i);
            var selected = i == optionIndex;
            var prefix = selected ? colorize.apply("accent", "> ") : "  ";
            var label = (i + 1) + ". " + opt.option().label()
                + (opt.isOther() && inputMode ? " ✎" : "");
            var slot = selected || (opt.isOther() && inputMode) ? "accent" : "text";
            lines.add(prefix + colorize.apply(slot, label));
            if (opt.option().description() != null) {
                lines.add("     " + colorize.apply("muted", opt.option().description()));
            }
        }
        return lines;
    }

    private static boolean isEscape(KeyStroke key) {
        return "esc".equals(key.key()) && !key.ctrl() && !key.alt();
    }

    private static boolean isEnter(KeyStroke key) {
        return "enter".equals(key.key()) && !key.ctrl() && !key.alt();
    }
}
