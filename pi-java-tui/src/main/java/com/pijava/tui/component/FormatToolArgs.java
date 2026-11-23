package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L7 W2（R26）：pi {@code render-utils.ts formatToolCallWithArgs} 的 Java 对位——
 * 通用工具调用参数渲染。collapsed＝{@code title k=v k=v …} 超 100 字符截断加
 * {@code ...}；expanded＝每 entry {@code   key: value} 行（字符串原样、复合值
 * {@code JSON.stringify(v, null, 2)} 两空格缩进、续行再缩 4）。返回<b>原始未转义</b>
 * 行，转义由调用方（MessageBubble）负责。
 */
public final class FormatToolArgs {

    /** pi {@code COLLAPSED_ARGS_CHARS}。 */
    static final int COLLAPSED_ARGS_CHARS = 100;

    private FormatToolArgs() {}

    /**
     * Formats the tool-call argument lines.
     *
     * @param title the tool title (already resolved, e.g. {@code "read"})
     * @param arguments the parsed arguments map ({@code null} ≙ pi 的无 args ⇒ 仅标题)
     * @param expanded whether the global output-expanded state is on
     * @return raw (unescaped) lines; first line starts with the title
     */
    public static List<String> lines(String title, Map<String, Object> arguments, boolean expanded) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of(title);
        }
        var out = new ArrayList<String>();
        if (!expanded) {
            var sb = new StringBuilder(title);
            for (var entry : arguments.entrySet()) {
                sb.append(' ').append(entry.getKey()).append('=').append(compact(entry.getValue()));
            }
            var line = sb.toString();
            out.add(line.length() > COLLAPSED_ARGS_CHARS
                ? line.substring(0, COLLAPSED_ARGS_CHARS) + "..."
                : line);
            return out;
        }
        for (var entry : arguments.entrySet()) {
            var segments = pretty(entry.getValue(), 0).split("\n", -1);
            out.add("  " + entry.getKey() + ": " + segments[0]);
            for (int i = 1; i < segments.length; i++) {
                out.add("    " + segments[i]); // pi：续行再缩 4
            }
        }
        return out;
    }

    /** collapsed 值形：字符串原样，其余紧凑 JSON（pi {@code formatArgValue(_, false)}）。 */
    private static String compact(Object value) {
        if (value instanceof String s) {
            return s;
        }
        return json(value, 0);
    }

    /** expanded 值形：字符串原样，其余 {@code JSON.stringify(v, null, 2)} 形。 */
    private static String pretty(Object value, int indent) {
        if (value instanceof String s) {
            return s;
        }
        return json(value, indent);
    }

    /** 极简 JSON writer：Map/List/Number/Boolean/null；{@code indent=0} ＝ 紧凑。 */
    private static String json(Object value, int indent) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return brace(map.isEmpty(), map.entrySet().stream()
                .map(e -> quote(String.valueOf(e.getKey())) + ": " + json(e.getValue(), indent + 1))
                .toList(), indent, '{', '}');
        }
        if (value instanceof Iterable<?> list) {
            var items = new ArrayList<String>();
            for (var item : list) {
                items.add(json(item, indent + 1));
            }
            return brace(items.isEmpty(), items, indent, '[', ']');
        }
        return quote(String.valueOf(value));
    }

    /** 多行容器体：空 ⇒ 空壳；非空 ⇒ 每项一行、尾项无逗号（JSON.stringify 语义）。 */
    private static String brace(boolean empty, List<String> items, int indent,
                                char open, char close) {
        if (empty) {
            return String.valueOf(open) + close;
        }
        var pad = "  ".repeat(indent);
        var innerPad = "  ".repeat(indent + 1);
        var sb = new StringBuilder().append(open).append('\n');
        for (int i = 0; i < items.size(); i++) {
            sb.append(innerPad).append(items.get(i));
            if (i < items.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        return sb.append(pad).append(close).toString();
    }

    /** JSON 字符串字面量（最小转义）。 */
    private static String quote(String text) {
        var sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
