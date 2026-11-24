package com.pijava.tui.util.latex;

import java.util.List;

/**
 * Markdown 文本中的 LaTeX 扫描（L7 W7，R8）：pi {@code markdown.ts:621-622,
 * 796-797} 的行内 {@code $…$} 与块级 {@code $$…$$} 识别。转义 {@code \$}
 * 不触发；渲染失败（不支持）⇒ 保留原文（pi 同）。
 */
public final class LatexInline {

    private LatexInline() {}

    /** 行内替换：{@code $…$} → Unicode；无匹配/不支持 ⇒ 原文。 */
    public static String inline(String text) {
        if (text == null || text.indexOf('$') < 0) {
            return text;
        }
        var out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
                continue;
            }
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 < text.length() && text.charAt(i + 1) == '$') {
                int close = text.indexOf("$$", i + 2);
                if (close < 0) {
                    out.append("$$");
                    i += 2;
                    continue;
                }
                out.append(renderOrRaw(text.substring(i + 2, close), true));
                i = close + 2;
                continue;
            }
            int close = findClosing(text, i + 1);
            if (close < 0) {
                out.append('$');
                i++;
                continue;
            }
            String sourceText = text.substring(i + 1, close);
            out.append(renderOrRaw(sourceText, false));
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * 块级数学：整段（可多行）为 {@code $$…$$} 时返回渲染行；否则空表。
     */
    public static List<String> block(String text) {
        if (text == null) {
            return List.of();
        }
        String trimmed = text.strip();
        if (!trimmed.startsWith("$$") || !trimmed.endsWith("$$") || trimmed.length() <= 4) {
            return List.of();
        }
        String rendered = LatexRenderer.render(trimmed.substring(2, trimmed.length() - 2), true);
        return rendered == null ? List.of() : List.of(rendered.split("\n", -1));
    }

    private static String renderOrRaw(String source, boolean display) {
        String rendered = LatexRenderer.render(source, display);
        return rendered == null ? (display ? "$$" + source + "$$" : "$" + source + "$") : rendered;
    }

    private static int findClosing(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '$') {
                if (i == start || Character.isWhitespace(text.charAt(i - 1))) {
                    return -1;
                }
                return i;
            }
            if (c == '\n') {
                return -1;
            }
        }
        return -1;
    }
}
