package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.tui.theme.TuiThemeBridge;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * 轻量代码语法高亮（P6-23）：把一行文本切分为带样式的片段。
 *
 * <p>按 pi 的九类语法着色：注释、关键字、函数、变量、字符串、数字、类型、
 * 操作符与标点；保守配色下未识别标识符保持基础样式（不染成变量色）。</p>
 *
 * <p><b>批 25（docs/36 T6）</b>：颜色改走主题令牌（{@code syntaxKeyword}/
 * {@code syntaxString}/{@code syntaxNumber}/{@code syntaxComment} 等九类
 * {@code syntax*}，现读活跃主题），取代硬编码 VSCode 色板；输入编辑器当前行着色
 * （{@code EditorElement}）与 Markdown 代码块高亮（{@code MarkdownRenderer}）
 * 两消费点同色。</p>
 */
public final class SyntaxHighlighter {

    /** 行内样式片段。 */
    public record Segment(String text, Style style) {}

    private static final Set<String> KEYWORDS = Set.of(
        "if", "else", "elif", "for", "while", "return", "def", "class", "import",
        "from", "new", "public", "private", "protected", "static", "final",
        "const", "let", "var", "function", "async", "await", "try", "catch",
        "finally", "throw", "true", "false", "null", "switch", "case", "break",
        "continue");

    private static final Set<String> TYPES = Set.of(
        "int", "long", "float", "double", "boolean", "char", "byte", "short", "void",
        "String", "Integer", "Boolean", "Long", "Double", "Float", "Character",
        "Byte", "Short", "List", "Map", "Set");

    private SyntaxHighlighter() {}

    /** 关键字色（{@code syntaxKeyword}，活跃主题现读）。 */
    public static Color keywordColor() {
        return TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_KEYWORD);
    }

    /** 字符串色（{@code syntaxString}）。 */
    public static Color stringColor() {
        return TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_STRING);
    }

    /** 数字色（{@code syntaxNumber}）。 */
    public static Color numberColor() {
        return TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_NUMBER);
    }

    /** 注释色（{@code syntaxComment}）。 */
    public static Color commentColor() {
        return TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_COMMENT);
    }

    /** 函数名色（{@code syntaxFunction}）。 */
    public static Color functionColor() {
        return TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_FUNCTION);
    }

    /** 变量名色（{@code syntaxVariable}）。 */
    public static Color variableColor() {
        return TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_VARIABLE);
    }

    /** 类型名色（{@code syntaxType}）。 */
    public static Color typeColor() {
        return TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_TYPE);
    }

    /** 操作符色（{@code syntaxOperator}）。 */
    public static Color operatorColor() {
        return TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_OPERATOR);
    }

    /** 标点色（{@code syntaxPunctuation}）。 */
    public static Color punctuationColor() {
        return TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_PUNCTUATION);
    }

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
                segments.add(new Segment(line.substring(i), base.fg(commentColor())));
                break;
            }
            if (ch == '"' || ch == '\'') {
                flush(segments, plain, base);
                int end = closingQuote(line, i, ch);
                segments.add(new Segment(line.substring(i, end + 1), base.fg(stringColor())));
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
                segments.add(new Segment(line.substring(i, j), base.fg(numberColor())));
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
                int k = j;
                while (k < line.length() && Character.isWhitespace(line.charAt(k))) {
                    k++;
                }
                if (KEYWORDS.contains(word)) {
                    flush(segments, plain, base);
                    segments.add(new Segment(word, base.fg(keywordColor())));
                } else if (TYPES.contains(word)) {
                    flush(segments, plain, base);
                    segments.add(new Segment(word, base.fg(typeColor())));
                } else if (k < line.length() && line.charAt(k) == '(') {
                    flush(segments, plain, base);
                    segments.add(new Segment(word, base.fg(functionColor())));
                } else {
                    plain.append(word);
                }
                i = j;
                continue;
            }
            if ("+-*/%=<>!&|^~?:".indexOf(ch) >= 0) {
                flush(segments, plain, base);
                segments.add(new Segment(String.valueOf(ch), base.fg(operatorColor())));
                i++;
                continue;
            }
            if (",;(){}[]".indexOf(ch) >= 0) {
                flush(segments, plain, base);
                segments.add(new Segment(String.valueOf(ch), base.fg(punctuationColor())));
                i++;
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
