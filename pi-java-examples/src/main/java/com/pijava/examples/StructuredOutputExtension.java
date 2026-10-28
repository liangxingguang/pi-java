package com.pijava.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ToolCallRenderer;

/**
 * pi {@code examples/extensions/structured-output.ts:18-65} 移植（docs/40 P15）——
 * 终止型结构化输出工具：{@code terminate: true} 让回合在工具结果后直接结束。
 *
 * <p>挂载点差异（窄差 D173）：pi 把 {@code renderResult} 挂在工具定义内；java
 * 走 {@code registerToolRenderer} 责任链、<b>文本级</b>（着色随 D92）。java 的
 * {@link ToolCallRenderer#renderResult} 只收文本、看不到 details ⇒ 工具执行时把
 * 最近一份 details 挂扩展字段，渲染器读（pi 模块级状态对位；单会话串行等价）。</p>
 */
public final class StructuredOutputExtension implements PiExtension {

    /** 最近一次执行的 details（渲染器读取面；null ≙ pi 的 details undefined 臂）。 */
    private volatile Map<String, Object> lastDetails;

    @Override
    public String name() {
        return "structured-output";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(new StructuredOutputTool(this::stash));
        ctx.registerToolRenderer((toolName, next) ->
            "structured_output".equals(toolName) ? new StructuredResultRenderer(next) : null);
    }

    private void stash(Map<String, Object> details) {
        this.lastDetails = details;
    }

    /** pi 的 structuredOutputTool（defineTool 字面量对位）。 */
    static final class StructuredOutputTool
            implements AgentTool<Map<String, Object>, Map<String, Object>> {

        private final java.util.function.Consumer<Map<String, Object>> detailsSink;

        StructuredOutputTool(java.util.function.Consumer<Map<String, Object>> detailsSink) {
            this.detailsSink = detailsSink;
        }

        @Override
        public String name() {
            return "structured_output";
        }

        @Override
        public String label() {
            return "Structured Output";
        }

        @Override
        public String promptSnippet() {
            return "Emit a final structured answer as a terminating tool result";
        }

        @Override
        public List<String> promptGuidelines() {
            return List.of(
                "Use structured_output as your final action when the user asks for"
                    + " structured output, JSON-like output, or a machine-readable summary.",
                "After calling structured_output, do not emit another assistant response"
                    + " in the same turn.");
        }

        @Override
        public String description() {
            return "Return a final structured answer. Use this as your last action when"
                + " the user asks for structured output or a machine-readable summary.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object",
                "required", List.of("headline", "summary", "actionItems"),
                "properties", JsonSchemas.obj(
                    "headline", JsonSchemas.obj("type", "string",
                        "description", "Short title for the result"),
                    "summary", JsonSchemas.obj("type", "string",
                        "description", "One-paragraph summary"),
                    "actionItems", JsonSchemas.obj("type", "array",
                        "items", JsonSchemas.obj("type", "string"),
                        "description", "Concrete next steps or key bullets")));
        }

        @Override
        public ExecutionMode executionMode() {
            return new ExecutionMode.Parallel();
        }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var headline = String.valueOf(params.get("headline"));
            var summary = String.valueOf(params.get("summary"));
            var actionItems = params.get("actionItems");
            var details = new LinkedHashMap<String, Object>();
            details.put("headline", headline);
            details.put("summary", summary);
            details.put("actionItems", actionItems);
            detailsSink.accept(details);
            return new ToolResult<>(
                List.of(new ContentBlock.TextContent("Saved structured output: " + headline)),
                details, null, true, List.of());
        }
    }

    /**
     * pi 的 renderResult（裁决④ 文本级两臂）：无 details ⇒ content 首文本；
     * 有 ⇒ headline／summary／空行／编号列表。
     */
    private final class StructuredResultRenderer implements ToolCallRenderer {

        private final Supplier<ToolCallRenderer> next;

        StructuredResultRenderer(Supplier<ToolCallRenderer> next) {
            this.next = next;
        }

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args,
                                       boolean expanded) {
            // pi 未自定义 renderCall ⇒ 交后续 resolver（不管）
            var fallback = next.get();
            return fallback == null ? List.of() : fallback.renderCall(toolName, args, expanded);
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            var details = lastDetails;
            if (details == null) {
                return textOutput.isEmpty() ? List.of() : List.of(textOutput);
            }
            var lines = new ArrayList<String>();
            lines.add(String.valueOf(details.get("headline")));
            lines.add(String.valueOf(details.get("summary")));
            lines.add("");
            var items = details.get("actionItems");
            if (items instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    lines.add((i + 1) + ". " + list.get(i));
                }
            }
            return lines;
        }
    }
}
