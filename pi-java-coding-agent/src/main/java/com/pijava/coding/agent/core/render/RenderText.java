package com.pijava.coding.agent.core.render;

import java.util.ArrayList;
import java.util.List;

/**
 * docs/60（L7 W1b）：内置渲染器的文本工具——转义、切行、JS 数字形态。
 *
 * <p>{@link #escape} 与 tui 的 {@code TextLayout.escapeMarkup} 逐字同形
 * （{@code [}→{@code [[}、{@code ]}→{@code ]]}、{@code \}→{@code \\}），
 * 但 coding-agent 不依赖 tui（分层纪律，{@code ToolCallRenderer} 同源），
 * 故在此重列一份。</p>
 */
final class RenderText {

    private RenderText() {}

    /** TamboUI markup 转义：任意用户文本按字面渲染。 */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        var out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '[' -> out.append("[[");
                case ']' -> out.append("]]");
                case '\\' -> out.append("\\\\");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** 逐行转义（空行保持空行）。 */
    static List<String> escapeAll(List<String> lines) {
        var out = new ArrayList<String>(lines.size());
        for (var line : lines) {
            out.add(escape(line));
        }
        return List.copyOf(out);
    }

    /** 按 {@code \n} 切行（保留行内空白；空文本 ⇒ 空表）。 */
    static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\n", -1));
    }

    /** 去掉尾部空行（pi {@code trimTrailingEmptyLines}）。 */
    static List<String> trimTrailingEmpty(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isEmpty()) {
            end--;
        }
        return end == lines.size() ? lines : List.copyOf(lines.subList(0, end));
    }

    /** JS {@code String(number)}：整值去小数点（30.0→"30"），其余原样。 */
    static String jsNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
            ? Long.toString((long) value) : Double.toString(value);
    }
}
