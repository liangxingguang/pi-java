package com.pijava.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ToolCallRenderer;

/**
 * pi {@code examples/extensions/question.ts:1-286} 移植（docs/27 W6）——
 * {@code question} 工具：给定问题与选项，弹自定义组件让用户选或手写答案。
 *
 * <p>逐字对位：非 tui 与空选项两条错误臂、附加 {@code "Type something."} 项、
 * 光标上下移动、Enter 选中/进入编辑、Esc 在编辑态回选项列表、Esc 在列表取消、
 * 编辑器提交（空文本复位回列表）、三种结果文本（{@code "User selected: N. x"}／
 * {@code "User wrote: x"}／{@code "User cancelled the selection"}）与 details 形状。</p>
 *
 * <p>窄差：
 * ① **D92/D241 同族**——{@code renderCall}/{@code renderResult} 在 java 是文本级
 * {@link ToolCallRenderer}（无 theme、无 {@code result.details}），故结果行按
 * {@code textOutput} 前缀复原；
 * ② 组件是文本级基座 {@link ExampleEditor} + 单行 {@code prefix + text}（无软换行）,
 * 见 {@link ExampleEditor} 的窄差声明。</p>
 */
public final class QuestionExtension implements PiExtension {

    /** 工具名（pi 逐字）。 */
    static final String TOOL_NAME = "question";

    /** pi L48：工具描述（逐字）。 */
    static final String DESCRIPTION =
        "Ask the user a question and let them pick from options. "
            + "Use when you need user input to proceed.";

    /** pi L71：附加项标签（逐字）。 */
    static final String OTHER_LABEL = "Type something.";

    /** pi L55：非交互模式错误（逐字）。 */
    static final String NO_UI_ERROR = "Error: UI not available (running in non-interactive mode)";

    /** pi L66：空选项错误（逐字）。 */
    static final String NO_OPTIONS_ERROR = "Error: No options provided";

    /** pi L225：取消文本（逐字）。 */
    static final String CANCELLED = "User cancelled the selection";

    @Override
    public String name() {
        return "question";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(new QuestionTool(ctx));
        ctx.registerToolRenderer((toolName, next) ->
            TOOL_NAME.equals(toolName) ? new QuestionRenderer() : next.get());
    }

    /** pi L19-24：选项（{@code description} 可空；{@code isOther} 是附加项标记）。 */
    record Option(String label, String description, boolean isOther) {

        static Option plain(String label, String description) {
            return new Option(label, description, false);
        }
    }

    /** pi L73-74 的 {@code done} 载荷。 */
    record Answer(String answer, boolean wasCustom, Integer index) {}

    /** pi L34-42：输入 schema（label 必填、description 可选）。 */
    static Map<String, Object> inputSchema() {
        return JsonSchemas.obj("type", "object", "properties", JsonSchemas.obj(
            "question", JsonSchemas.obj("type", "string",
                "description", "The question to ask the user"),
            "options", JsonSchemas.obj("type", "array",
                "description", "Options for the user to choose from",
                "items", JsonSchemas.obj("type", "object",
                    "properties", JsonSchemas.obj(
                        "label", JsonSchemas.obj("type", "string",
                            "description", "Display label for the option"),
                        "description", JsonSchemas.obj("type", "string",
                            "description", "Optional description shown below label")),
                    "required", List.of("label")))));
    }

    /** pi L221：details 的 {@code options} 是纯标签表。 */
    static List<String> labels(List<Option> options) {
        return options.stream().map(Option::label).toList();
    }

    /** pi L71：显示项 = 入参选项 + {@code Type something.}。 */
    static List<Option> displayOptions(List<Option> options) {
        var all = new ArrayList<>(options);
        all.add(new Option(OTHER_LABEL, null, true));
        return List.copyOf(all);
    }

    /** pi L223-249：结果三臂（返回文本 + details）。 */
    static ToolResult<Map<String, Object>> result(Answer answer, String question,
                                                  List<String> labels) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("question", question);
        details.put("options", labels);
        if (answer == null) {
            details.put("answer", null);
            return ToolResult.success(CANCELLED, details);
        }
        details.put("answer", answer.answer());
        details.put("wasCustom", answer.wasCustom());
        var text = answer.wasCustom()
            ? "User wrote: " + answer.answer()
            : "User selected: " + answer.index() + ". " + answer.answer();
        return ToolResult.success(text, details);
    }

    /** 把入参 options 解成 {@link Option}（缺 label 的项跳过，等同 pi 的 undefined 标签）。 */
    static List<Option> parseOptions(Object raw) {
        var out = new ArrayList<Option>();
        if (raw instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map<?, ?> map && map.get("label") instanceof String label) {
                    out.add(Option.plain(label,
                        map.get("description") instanceof String d ? d : null));
                }
            }
        }
        return List.copyOf(out);
    }

    /** pi L45-250：工具本体。 */
    static final class QuestionTool
            implements AgentTool<Map<String, Object>, Map<String, Object>> {

        private final ExtensionContext ctx;

        QuestionTool(ExtensionContext ctx) {
            this.ctx = ctx;
        }

        @Override public String name() { return TOOL_NAME; }
        @Override public String label() { return "Question"; }
        @Override public String description() { return DESCRIPTION; }
        @Override public Map<String, Object> inputSchema() { return QuestionExtension.inputSchema(); }

        /** pi L50：{@code executionMode: "sequential"}。 */
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var question = params.get("question") instanceof String q ? q : "";
            var options = parseOptions(params.get("options"));

            if (!"tui".equals(ctx.mode())) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("question", question);
                details.put("options", labels(options));
                details.put("answer", null);
                return ToolResult.success(NO_UI_ERROR, details);
            }
            if (options.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("question", question);
                details.put("options", List.of());
                details.put("answer", null);
                return ToolResult.success(NO_OPTIONS_ERROR, details);
            }

            var answer = ctx.ui().<Answer>custom(
                (env, keybindings, done) -> new QuestionComponent(question,
                    displayOptions(options), ExampleEditor.colorizeOf(env), done), null);
            return result(answer, question, labels(options));
        }
    }

    /** pi L73-218：自定义选择组件（选项列表 + 内联编辑器）。 */
    static final class QuestionComponent implements ExtensionComponent {

        private final String question;
        private final List<Option> options;
        private final BiFunction<String, String, String> colorize;
        private final Consumer<Answer> done;
        private final ExampleEditor editor;
        private int optionIndex;
        private boolean editMode;

        QuestionComponent(String question, List<Option> options,
                          BiFunction<String, String, String> colorize,
                          Consumer<Answer> done) {
            this.question = question;
            this.options = options;
            this.colorize = colorize == null ? (slot, text) -> text : colorize;
            this.done = done;
            this.editor = new ExampleEditor(colorize);
        }

        /** 当前光标位置（测试探针）。 */
        int optionIndex() {
            return optionIndex;
        }

        /** 是否在编辑态（测试探针）。 */
        boolean editMode() {
            return editMode;
        }

        /** 内联编辑器（测试探针）。 */
        ExampleEditor editor() {
            return editor;
        }

        /** pi L107-145：输入分派。 */
        @Override
        public void handleInput(KeyStroke key) {
            if (editMode) {
                if (isEscape(key)) {
                    editMode = false;
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
            if ("up".equals(key.key())) {
                optionIndex = Math.max(0, optionIndex - 1);
                return;
            }
            if ("down".equals(key.key())) {
                optionIndex = Math.min(options.size() - 1, optionIndex + 1);
                return;
            }
            if (isEnter(key)) {
                var selected = options.get(optionIndex);
                if (selected.isOther()) {
                    editMode = true;
                } else {
                    done.accept(new Answer(selected.label(), false, optionIndex + 1));
                }
                return;
            }
            if (isEscape(key)) {
                done.accept(null);
            }
        }

        /** pi L91-100：编辑器提交（空文本复位回列表）。 */
        void submitEditor() {
            var trimmed = editor.getText().trim();
            if (!trimmed.isEmpty()) {
                done.accept(new Answer(trimmed, true, null));
                return;
            }
            editMode = false;
            editor.setText("");
        }

        /** pi L147-208：文本级渲染。 */
        @Override
        public List<String> render(int width) {
            var renderWidth = Math.max(1, width);
            var lines = new ArrayList<String>();
            lines.add(colorize.apply("accent", ExampleEditor.BORDER_GLYPH.repeat(renderWidth)));
            lines.add(" " + colorize.apply("text", question));
            lines.add("");
            for (int i = 0; i < options.size(); i++) {
                var opt = options.get(i);
                var selected = i == optionIndex;
                var prefix = selected ? colorize.apply("accent", "> ") : "  ";
                var label = (i + 1) + ". " + opt.label()
                    + (opt.isOther() && editMode ? " ✎" : "");
                var slot = selected || (opt.isOther() && editMode) ? "accent" : "text";
                lines.add(prefix + colorize.apply(slot, label));
                if (opt.description() != null) {
                    lines.add("     " + colorize.apply("muted", opt.description()));
                }
            }
            if (editMode) {
                lines.add("");
                lines.add(" " + colorize.apply("muted", "Your answer:"));
                for (var line : editor.render(Math.max(1, renderWidth - 2))) {
                    lines.add(" " + line);
                }
            }
            lines.add("");
            lines.add(" " + colorize.apply("dim", editMode
                ? "Enter to submit • Esc to go back"
                : "↑↓ navigate • Enter to select • Esc to cancel"));
            lines.add(colorize.apply("accent", ExampleEditor.BORDER_GLYPH.repeat(renderWidth)));
            return List.copyOf(lines);
        }

        private static boolean isEscape(KeyStroke key) {
            return "esc".equals(key.key()) && !key.ctrl() && !key.alt();
        }

        private static boolean isEnter(KeyStroke key) {
            return "enter".equals(key.key()) && !key.ctrl() && !key.alt();
        }
    }

    /** pi L252-284：文本级工具渲染器（D241/D254）。 */
    static final class QuestionRenderer implements ToolCallRenderer {

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args,
                                       boolean expanded) {
            var question = args.get("question") instanceof String q ? q : "";
            var text = "question " + question;
            var options = QuestionExtension.parseOptions(args.get("options"));
            if (!options.isEmpty()) {
                var labels = new ArrayList<>(QuestionExtension.labels(options));
                labels.add(OTHER_LABEL);
                var numbered = new ArrayList<String>();
                for (int i = 0; i < labels.size(); i++) {
                    numbered.add((i + 1) + ". " + labels.get(i));
                }
                text += "\n  Options: " + String.join(", ", numbered);
            }
            return List.of(text.split("\n", -1));
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            var text = textOutput == null ? "" : textOutput;
            if (text.startsWith("User selected: ")) {
                return List.of("✓ " + text.substring("User selected: ".length()));
            }
            if (text.startsWith("User wrote: ")) {
                return List.of("✓ (wrote) " + text.substring("User wrote: ".length()));
            }
            if (CANCELLED.equals(text)) {
                return List.of("Cancelled");
            }
            return List.of(text);
        }
    }
}
