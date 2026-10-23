package com.pijava.coding.agent.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批 16（T4c，docs/27）：pi 内置 MCP 扩展的工具渲染器
 * （extensions/mcp/index.ts:384-386 的 resolver ＋ tools.ts:298-339 的
 * {@code createMcpToolRenderers}）的 java 文本级对位。
 *
 * <p>label＝{@code <server>/<tool>}（正则 {@code ^mcp__(.+?)__(.+)$} 逐字）；
 * {@code next() ??} 委托优先逐字（先让后续 resolver/注册工具发言）。</p>
 *
 * <p>折叠臂＝首 {@value #OUTPUT_PREVIEW_LINES} 行＋{@code ... (N more lines)}
 * 提示行（pi VisualLinePreview 的文本级对位——pi 数<b>视觉</b>行且带 keyHint，
 * java 数逻辑行、无 keyHint 缝，窄差 D92）；展开臂全量。
 * pi 的 {@code Full output: <path>} 行依赖 {@code details.fullOutputPath}，
 * java 渲染接口无 details 通道（D92 含此项）。</p>
 */
public final class McpToolCallRenderer {

    /** pi tools.ts:53（{@code OUTPUT_PREVIEW_LINES = 5}）。 */
    static final int OUTPUT_PREVIEW_LINES = 5;
    /** pi render-utils.ts:76（{@code COLLAPSED_ARGS_CHARS = 100}）。 */
    static final int COLLAPSED_ARGS_CHARS = 100;

    private static final Pattern MCP_NAME = Pattern.compile("^mcp__(.+?)__(.+)$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private McpToolCallRenderer() {}

    /**
     * pi extensions/mcp/index.ts:384-386 逐字语义：
     * {@code next() ?? (match ? createMcpToolRenderers(label) : undefined)}。
     */
    public static ToolRendererResolver resolver() {
        return (toolName, next) -> {
            var delegated = next.get();
            if (delegated != null) {
                return delegated;
            }
            var matcher = MCP_NAME.matcher(toolName);
            if (!matcher.matches()) {
                return null;
            }
            return create(matcher.group(1) + "/" + matcher.group(2));
        };
    }

    /** pi {@code createMcpToolRenderers(label)}（tools.ts:298-339）的文本级对位。 */
    static ToolCallRenderer create(String label) {
        return new ToolCallRenderer() {
            @Override
            public List<String> renderCall(String toolName, Map<String, Object> args,
                                           boolean expanded) {
                return formatToolCallWithArgs(label, args, expanded);
            }

            @Override
            public List<String> renderResult(String toolName, String textOutput,
                                             boolean isError, boolean expanded) {
                var output = textOutput == null ? "" : textOutput.trim();
                if (output.isEmpty()) {
                    return List.of(); // pi：空 output ⇒ 空组件
                }
                var lines = output.replace("\r", "").replace("\t", "   ").split("\n", -1);
                if (expanded || lines.length <= OUTPUT_PREVIEW_LINES) {
                    return List.of(lines);
                }
                var preview = new ArrayList<String>(OUTPUT_PREVIEW_LINES + 1);
                for (int i = 0; i < OUTPUT_PREVIEW_LINES; i++) {
                    preview.add(lines[i]);
                }
                preview.add("... (" + (lines.length - OUTPUT_PREVIEW_LINES) + " more lines)");
                return List.copyOf(preview);
            }
        };
    }

    /**
     * pi {@code formatToolCallWithArgs}（render-utils.ts:78-101）的文本级对位：
     * 折叠臂 {@code label k=v ...}（{@link #COLLAPSED_ARGS_CHARS} 截断）；
     * 展开臂 {@code label} 下挂 {@code "  key: value"} 行（字符串原样、其余 JSON
     * 两行缩进、续行再缩 4）。
     */
    static List<String> formatToolCallWithArgs(String title, Map<String, Object> args,
                                               boolean expanded) {
        if (args == null || args.isEmpty()) {
            return List.of(title);
        }
        if (expanded) {
            var lines = new ArrayList<String>();
            lines.add(title);
            for (var entry : args.entrySet()) {
                var text = entry.getValue() instanceof String s
                    ? s : json(entry.getValue(), true);
                var indented = text.replace("\t", "   ").replace("\r", "")
                    .split("\n", -1);
                lines.add("  " + entry.getKey() + ": " + indented[0]);
                for (int i = 1; i < indented.length; i++) {
                    lines.add("    " + indented[i]);
                }
            }
            return List.copyOf(lines);
        }
        var pairs = new StringBuilder();
        for (var entry : args.entrySet()) {
            if (pairs.length() > 0) {
                pairs.append(' ');
            }
            pairs.append(entry.getKey()).append('=').append(json(entry.getValue(), false));
        }
        var preview = pairs.length() > COLLAPSED_ARGS_CHARS
            ? pairs.substring(0, COLLAPSED_ARGS_CHARS - 3) + "..." : pairs.toString();
        return List.of(title + " " + preview);
    }

    /** pi {@code JSON.stringify(value)}（折叠臂紧凑／展开臂两空格缩进）。 */
    private static String json(Object value, boolean pretty) {
        try {
            return pretty
                ? JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                : JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(value); // pi 的 ?? String(value) 回落
        }
    }
}
