package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ToolCallRenderer;

/**
 * pi {@code examples/extensions/questionnaire.ts:1-448} 移植（docs/27 W6）——
 * {@code questionnaire} 工具：一次问多个问题（单问题退化为普通列表，多问题带
 * 页签条与提交页）。
 *
 * <p>逐字对位：非 tui／空问题两条错误臂、{@code label} 缺省 {@code Q1..Qn}、
 * {@code allowOther} 缺省 true、{@code "Type something."} 项（value
 * {@code "__other__"}）、Tab/←→ 切页（多问题才启用）、提交页 Enter 需全答、
 * Esc 取消、答案表按提交序输出，内容行 {@code "Label: user selected: N. x"}／
 * {@code "Label: user wrote: x"}。交互状态机在 {@link QuestionnaireComponent}。</p>
 *
 * <p>窄差同 {@link QuestionExtension}：文本级组件与文本级工具渲染器（D92/D241）。</p>
 */
public final class QuestionnaireExtension implements PiExtension {

    /** 工具名（pi 逐字）。 */
    static final String TOOL_NAME = "questionnaire";

    /** pi L88-89：工具描述（逐字）。 */
    static final String DESCRIPTION =
        "Ask the user one or more questions. Use for clarifying requirements, "
            + "getting preferences, or confirming decisions. For single questions, shows a "
            + "simple option list. For multiple questions, shows a tab-based interface.";

    /** pi L151：附加项 value（逐字）。 */
    static final String OTHER_VALUE = "__other__";

    /** pi L151：附加项标签（逐字）。 */
    static final String OTHER_LABEL = "Type something.";

    /** pi L181：手写答案留空时的占位（逐字）。 */
    static final String NO_RESPONSE = "(no response)";

    /** pi L94：非交互模式错误（逐字）。 */
    static final String NO_UI_ERROR = "Error: UI not available (running in non-interactive mode)";

    /** pi L97：空问题错误（逐字）。 */
    static final String NO_QUESTIONS_ERROR = "Error: No questions provided";

    /** pi L398：取消文本（逐字）。 */
    static final String CANCELLED = "User cancelled the questionnaire";

    @Override
    public String name() {
        return "questionnaire";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(new QuestionnaireTool(ctx));
        ctx.registerToolRenderer((toolName, next) ->
            TOOL_NAME.equals(toolName) ? new QuestionnaireRenderer() : next.get());
    }

    /** pi L21-25：选项（value 是返回值、label 是显示）。 */
    record QuestionOption(String value, String label, String description) {}

    /** pi L27：显示项（附加项带 {@code isOther}）。 */
    record RenderOption(QuestionOption option, boolean isOther) {}

    /** pi L29-35：一个问题的规范化形（label/allowOther 已有缺省）。 */
    record Question(String id, String label, String prompt, List<QuestionOption> options,
                    boolean allowOther) {}

    /** pi L37-43：一条答复。 */
    record Answer(String id, String value, String label, boolean wasCustom, Integer index) {}

    /** pi L45-49：组件返回值。 */
    record QuestionnaireResult(List<Question> questions, List<Answer> answers,
                               boolean cancelled) {}

    /** pi L52-72：输入 schema。 */
    static Map<String, Object> inputSchema() {
        return JsonSchemas.obj("type", "object", "properties", JsonSchemas.obj(
            "questions", JsonSchemas.obj("type", "array",
                "description", "Questions to ask the user",
                "items", JsonSchemas.obj("type", "object",
                    "properties", JsonSchemas.obj(
                        "id", JsonSchemas.obj("type", "string",
                            "description", "Unique identifier for this question"),
                        "label", JsonSchemas.obj("type", "string",
                            "description", "Short contextual label for tab bar, "
                                + "e.g. 'Scope', 'Priority' (defaults to Q1, Q2)"),
                        "prompt", JsonSchemas.obj("type", "string",
                            "description", "The full question text to display"),
                        "options", JsonSchemas.obj("type", "array",
                            "description", "Available options to choose from",
                            "items", JsonSchemas.obj("type", "object",
                                "properties", JsonSchemas.obj(
                                    "value", JsonSchemas.obj("type", "string",
                                        "description", "The value returned when selected"),
                                    "label", JsonSchemas.obj("type", "string",
                                        "description", "Display label for the option"),
                                    "description", JsonSchemas.obj("type", "string",
                                        "description",
                                        "Optional description shown below label")),
                                "required", List.of("value", "label"))),
                        "allowOther", JsonSchemas.obj("type", "boolean",
                            "description",
                            "Allow 'Type something' option (default: true)")),
                    "required", List.of("id", "prompt", "options")))));
    }

    /** pi L74-82：错误结果。 */
    static ToolResult<QuestionnaireResult> errorResult(String message, List<Question> questions) {
        return ToolResult.success(message,
            new QuestionnaireResult(questions, List.of(), true));
    }

    /** pi L101-105：规范化（label 缺省 {@code Q<n>}、allowOther 缺省 true）。 */
    static List<Question> normalize(Object raw) {
        var out = new ArrayList<Question>();
        if (raw instanceof List<?> list) {
            for (var item : list) {
                if (!(item instanceof Map<?, ?> map) || !(map.get("id") instanceof String id)) {
                    continue;
                }
                var index = out.size();
                var label = map.get("label") instanceof String l && !l.isEmpty()
                    ? l : "Q" + (index + 1);
                var prompt = map.get("prompt") instanceof String p ? p : "";
                var allowOther = !Boolean.FALSE.equals(map.get("allowOther"));
                out.add(new Question(id, label, prompt, parseOptions(map.get("options")),
                    allowOther));
            }
        }
        return List.copyOf(out);
    }

    private static List<QuestionOption> parseOptions(Object raw) {
        var out = new ArrayList<QuestionOption>();
        if (raw instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map<?, ?> map && map.get("value") instanceof String value) {
                    var label = map.get("label") instanceof String l ? l : value;
                    out.add(new QuestionOption(value, label,
                        map.get("description") instanceof String d ? d : null));
                }
            }
        }
        return List.copyOf(out);
    }

    /** pi L146-154：当前问题的显示项（{@code allowOther} 才追加附加项）。 */
    static List<RenderOption> currentOptions(Question question) {
        if (question == null) {
            return List.of();
        }
        var opts = new ArrayList<RenderOption>();
        question.options().forEach(o -> opts.add(new RenderOption(o, false)));
        if (question.allowOther()) {
            opts.add(new RenderOption(new QuestionOption(OTHER_VALUE, OTHER_LABEL, null), true));
        }
        return List.copyOf(opts);
    }

    /** pi L403-414：结果载荷（内容行 + details）。 */
    static ToolResult<QuestionnaireResult> result(QuestionnaireResult result,
                                                  List<Question> questions) {
        if (result.cancelled()) {
            return ToolResult.success(CANCELLED, result);
        }
        var lines = new ArrayList<String>();
        for (var answer : result.answers()) {
            var label = questions.stream().filter(q -> q.id().equals(answer.id()))
                .map(Question::label).findFirst().orElse(answer.id());
            lines.add(answer.wasCustom()
                ? label + ": user wrote: " + answer.label()
                : label + ": user selected: " + answer.index() + ". " + answer.label());
        }
        return ToolResult.success(String.join("\n", lines), result);
    }

    /** pi L85-415：工具本体。 */
    static final class QuestionnaireTool
            implements AgentTool<Map<String, Object>, QuestionnaireResult> {

        private final ExtensionContext ctx;

        QuestionnaireTool(ExtensionContext ctx) {
            this.ctx = ctx;
        }

        @Override public String name() { return TOOL_NAME; }
        @Override public String label() { return "Questionnaire"; }
        @Override public String description() { return DESCRIPTION; }

        @Override public Map<String, Object> inputSchema() {
            return QuestionnaireExtension.inputSchema();
        }

        @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

        @Override
        public ToolResult<QuestionnaireResult> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<QuestionnaireResult> onUpdate, ToolContext context) {
            if (!"tui".equals(ctx.mode())) {
                return errorResult(NO_UI_ERROR, List.of());
            }
            var questions = normalize(params.get("questions"));
            if (questions.isEmpty()) {
                return errorResult(NO_QUESTIONS_ERROR, List.of());
            }
            var result = ctx.ui().<QuestionnaireResult>custom(
                (env, keybindings, done) -> new QuestionnaireComponent(questions,
                    ExampleEditor.colorizeOf(env), done), null);
            if (result == null) {
                return errorResult(CANCELLED, questions);
            }
            return result(result, questions);
        }
    }

    /** pi L417-446：文本级工具渲染器（D241/D254）。 */
    static final class QuestionnaireRenderer implements ToolCallRenderer {

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args,
                                       boolean expanded) {
            var questions = normalize(args.get("questions"));
            var count = questions.size();
            var text = "questionnaire " + count + " question" + (count == 1 ? "" : "s");
            if (!questions.isEmpty()) {
                var labels = questions.stream().map(Question::label).toList();
                text += " (" + String.join(", ", labels) + ")";
            }
            return List.of(text);
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            var text = textOutput == null ? "" : textOutput;
            if (CANCELLED.equals(text)) {
                return List.of("Cancelled");
            }
            var lines = new ArrayList<String>();
            for (var line : text.split("\n", -1)) {
                lines.add(line.isEmpty() ? line : "✓ " + line);
            }
            return List.copyOf(lines);
        }
    }
}
