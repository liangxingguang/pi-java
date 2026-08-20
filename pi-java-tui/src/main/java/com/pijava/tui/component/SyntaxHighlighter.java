package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * 轻量代码语法高亮（P6-23）：把一行文本切分为带样式的片段。
 *
 * <p>识别关键字、字符串字面量、数字与 {@code //}、{@code #} 行注释；其余文本
 * 保持基础样式。用于输入编辑器当前行的着色。颜色取自 pi 暗色主题的语法色板。</p>
 */
public final class SyntaxHighlighter {

    /** 行内样式片段。 */
    public record Segment(String text, Style style) {}

    private static final Color KEYWORD = Color.hex("#569CD6");
    private static final Color STRING = Color.hex("#CE9178");
    private static final Color NUMBER = Color.hex("#B5CEA8");

    private static final Set<String> KEYWORDS = Set.of(
        "if", "else", "elif", "for", "while", "return", "def", "class", "import",
        "from", "new", "public", "private", "protected", "static", "final", "void",
        "int", "string", "boolean", "const", "let", "var", "function", "async",
        "await", "try", "catch", "finally", "throw", "true", "false", "null",
        "switch", "case", "break", "continue");

    private SyntaxHighlighter() {}

    /**
     * 高亮一行文本。
     *
     * @param line 单行文本（不含换行）
     * @param base 基础样式（片段样式在其上叠加前景色）
     * @return 覆盖整行的连续片段列表
     */
    public static List<Segment> highlight(String line, Style base) {
        var segments = new ArrayList<Segment>();
        var plain = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if ((ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') || ch == '#') {
                flush(segments, plain, base);
                segments.add(new Segment(line.substring(i), base.dim()));
                break;
            }
            if (ch == '"' || ch == '\'') {
                flush(segments, plain, base);
                int end = closingQuote(line, i, ch);
                segments.add(new Segment(line.substring(i, end + 1), base.fg(STRING)));
                i = end + 1;
                continue;
            }
            if (Character.isDigit(ch)) {
                flush(segments, plain, base);
                int j = i;
                while (j < line.length()
                        && (Character.isDigit(line.charAt(j)) || line.charAt(j) == '.')) {
                    j++;
                }
                segments.add(new Segment(line.substring(i, j), base.fg(NUMBER)));
                i = j;
                continue;
            }
            if (Character.isLetter(ch)) {
                int j = i;
                while (j < line.length()
                        && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '_')) {
                    j++;
                }
                var word = line.substring(i, j);
                if (KEYWORDS.contains(word)) {
                    flush(segments, plain, base);
                    segments.add(new Segment(word, base.fg(KEYWORD)));
                } else {
                    plain.append(word);
                }
                i = j;
                continue;
            }
            plain.append(ch);
            i++;
        }
        flush(segments, plain, base);
        return segments;
    }

    private static void flush(List<Segment> segments, StringBuilder plain, Style base) {
        if (plain.length() > 0) {
            segments.add(new Segment(plain.toString(), base));
            plain.setLength(0);
        }
    }

    private static int closingQuote(String line, int start, char quote) {
        for (int i = start + 1; i < line.length(); i++) {
            if (line.charAt(i) == '\\') {
                i++;
            } else if (line.charAt(i) == quote) {
                return i;
            }
        }
        return line.length() - 1;
    }
}
