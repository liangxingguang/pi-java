package com.pijava.tui.util;

import java.util.List;

/**
 * L7 W6（C13/C4/选择/搜索）：markup 感知的列工具。
 *
 * <p>与 {@link TextLayout} 的 wrap 同口径（标签零宽、转义解码、宽字符 2 列）：
 * 截断（{@code …}）、取可见文本、按显示列切片、列→字符偏移。单独成件以
 * 守 {@link TextLayout} 的 500 行 Checkstyle 限。</p>
 */
public final class TextColumns {

    private TextColumns() {}

    /**
     * 截断到目标显示宽：超宽时保留一个单元格给省略号。
     *
     * @param markup 源串（含可选标签/转义）
     * @param width  目标显示宽（&lt;1 时只输出省略号）
     * @return 截断后的 markup 串
     */
    public static String truncateToWidth(String markup, int width) {
        if (markup == null || markup.isEmpty()) {
            return "";
        }
        if (MarkupTokens.displayWidth(markup) <= width) {
            return markup;
        }
        var sb = new StringBuilder();
        int used = 0;
        int usable = Math.max(0, width - 1);
        for (var atom : TextLayout.atomsOf(markup)) {
            if (atom instanceof TextLayout.Atom.Tag tag) {
                sb.append(tag.raw());
                continue;
            }
            var ch = (TextLayout.Atom.Ch) atom;
            if (used + ch.width() > usable) {
                break;
            }
            sb.append(ch.emit());
            used += ch.width();
        }
        return sb.append('…').toString();
    }

    /**
     * 取 markup 的可见文本（去标签、解转义）。
     *
     * @param markup 源串
     * @return 可见文本（{@code null} ⇒ 空串）
     */
    public static String plainText(String markup) {
        if (markup == null || markup.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder(markup.length());
        for (var token : MarkupTokens.tokenize(markup)) {
            if (token instanceof MarkupTokens.Token.Text text) {
                sb.append(MarkupTokens.visibleText(text.raw()));
            }
        }
        return sb.toString();
    }

    /**
     * 按显示列切可见文本——列区间 {@code [startCol,endCol)}，宽字符整体纳入。
     *
     * @param text     可见文本（已去 markup）
     * @param startCol 起始显示列
     * @param endCol   结束显示列（不含）
     * @return 切出的子串
     */
    public static String sliceByColumns(String text, int startCol, int endCol) {
        if (text == null || text.isEmpty() || endCol <= startCol) {
            return "";
        }
        var sb = new StringBuilder();
        int col = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int w = TextLayout.isWide(cp) ? 2 : 1;
            int next = col + w;
            if (next > startCol && col < endCol) {
                sb.appendCodePoint(cp);
            }
            col = next;
            i += Character.charCount(cp);
            if (col >= endCol) {
                break;
            }
        }
        return sb.toString();
    }

    /** 可见文本在列 {@code 0..col} 的字符偏移（光标定位用）。 */
    public static int charIndexAtColumn(String text, int col) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int current = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int w = TextLayout.isWide(cp) ? 2 : 1;
            if (col < current + w) {
                return i;
            }
            current += w;
            i += Character.charCount(cp);
        }
        return text.length();
    }

    /** 可见文本的显示宽（逐码点，与 {@link #sliceByColumns} 同口径）。 */
    public static int width(String text) {
        return TextLayout.displayWidth(text);
    }

    /** 对多行文本逐行切列（选区/命中共用）。 */
    public static List<String> sliceLines(List<String> lines, int startRow, int startCol,
                                          int endRow, int endCol) {
        var out = new java.util.ArrayList<String>();
        for (int row = startRow; row <= endRow && row < lines.size(); row++) {
            String line = lines.get(row);
            int from = row == startRow ? startCol : 0;
            int to = row == endRow ? endCol : width(line) + 1;
            out.add(sliceByColumns(line, from, to));
        }
        return out;
    }
}
