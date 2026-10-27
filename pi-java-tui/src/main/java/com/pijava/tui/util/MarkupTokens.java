package com.pijava.tui.util;

import java.util.ArrayList;
import java.util.List;

/**
 * TamboUI markup 扫描器（批 25/docs/36 T1）：markup 串 → token 流
 * （开标签/闭标签/文本段）。扫描语义逐字镜像
 * {@code dev.tamboui.text.MarkupParser}（0.4.0 javap 取证）：
 *
 * <ul>
 *   <li>转义三件套——{@code [[}/{@code ]]}/{@code \}＋{@code []\} 归并为字面量文本
 *       （{@code \x}（x 非元字符）＝字面 {@code \}）；</li>
 *   <li>{@code [} 起试扫标签：可选 {@code /} 前缀（闭标签），名字扫到
 *       {@code ]}/{@code =}/{@code [}/换行——遇 {@code [}/换行则整段不是标签，
 *       {@code [} 退为字面量；{@code =} 后属性值扫到 {@code ]}/换行；</li>
 *   <li>空名非闭标签（{@code []}）整段退为字面量文本；名字到串尾未闭合仍算标签
 *       （parser 串尾 quirk）；标签名比较口径＝小写＋trim。</li>
 * </ul>
 *
 * <p>用途：wrap 地基（{@link TextLayout}）的量宽/切词跳过标签，表格列宽量算。
 * 标签零宽；文本段按解码后字符计列（宽字符 2 列，{@link TextLayout#isWide}）。</p>
 */
public final class MarkupTokens {

    /** 扫描 token：开/闭标签或文本段。 */
    public sealed interface Token {
        /**
         * 开标签（{@code [name]}/{@code [name=attr]}）。
         *
         * @param raw       原始串（含方括号，原样回发射用）
         * @param name      小写 trim 后的标签名
         * @param attribute {@code =} 后的属性值（无属性 ⇒ {@code null}）
         */
        record Open(String raw, String name, String attribute) implements Token {}

        /**
         * 闭标签（{@code [/]}/{@code [/name]}）。
         *
         * @param name {@code [/]} 为空串（弹一层）
         */
        record Close(String raw, String name) implements Token {}

        /** 文本段（raw 保留 {@code [[}/{@code ]]}/{@code \\} 转义形，未解码）。 */
        record Text(String raw) implements Token {}
    }

    private MarkupTokens() {}

    /** 切 token 流（{@code null}/空 ⇒ 空表）。 */
    public static List<Token> tokenize(String markup) {
        var tokens = new ArrayList<Token>();
        if (markup == null || markup.isEmpty()) {
            return tokens;
        }
        var text = new StringBuilder();
        int i = 0;
        int n = markup.length();
        while (i < n) {
            char c = markup.charAt(i);
            if (c == '\\' && i + 1 < n && isMeta(markup.charAt(i + 1))) {
                text.append(c).append(markup.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '[') {
                if (i + 1 < n && markup.charAt(i + 1) == '[') {
                    text.append("[[");
                    i += 2;
                    continue;
                }
                var scan = scanTag(markup, i);
                if (scan == null) {
                    text.append(c);                 // 不是标签：[ 退为字面量
                    i++;
                    continue;
                }
                if (scan.token() == null) {          // 空名非闭：整段字面量
                    text.append(markup, i, scan.end());
                    i = scan.end();
                    continue;
                }
                flush(tokens, text);
                tokens.add(scan.token());
                i = scan.end();
                continue;
            }
            if (c == ']' && i + 1 < n && markup.charAt(i + 1) == ']') {
                text.append("]]");
                i += 2;
                continue;
            }
            text.append(c);
            i++;
        }
        flush(tokens, text);
        return tokens;
    }

    /** markup 感知可见宽：标签零宽，文本段解码后计列。 */
    public static int displayWidth(String markup) {
        int width = 0;
        for (var token : tokenize(markup)) {
            if (token instanceof Token.Text text) {
                width += textWidth(text.raw());
            }
        }
        return width;
    }

    /** 文本段 raw → 可见字符串（解三件套转义）。 */
    public static String visibleText(String rawText) {
        var out = new StringBuilder(rawText.length());
        int i = 0;
        int n = rawText.length();
        while (i < n) {
            char c = rawText.charAt(i);
            if (c == '\\' && i + 1 < n && isMeta(rawText.charAt(i + 1))) {
                out.append(rawText.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '[' && i + 1 < n && rawText.charAt(i + 1) == '[') {
                out.append('[');
                i += 2;
                continue;
            }
            if (c == ']' && i + 1 < n && rawText.charAt(i + 1) == ']') {
                out.append(']');
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 文本段 raw 的可见宽（解码后按 {@link TextLayout#isWide} 计列）。 */
    static int textWidth(String rawText) {
        int width = 0;
        int i = 0;
        int n = rawText.length();
        while (i < n) {
            char c = rawText.charAt(i);
            if (c == '\\' && i + 1 < n && isMeta(rawText.charAt(i + 1))) {
                width += TextLayout.isWide(rawText.charAt(i + 1)) ? 2 : 1;
                i += 2;
                continue;
            }
            if ((c == '[' || c == ']') && i + 1 < n && rawText.charAt(i + 1) == c) {
                width += 1;
                i += 2;
                continue;
            }
            int cp = rawText.codePointAt(i);
            width += TextLayout.isWide(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    private static boolean isMeta(char c) {
        return c == '[' || c == ']' || c == '\\';
    }

    private static void flush(List<Token> tokens, StringBuilder text) {
        if (text.length() > 0) {
            tokens.add(new Token.Text(text.toString()));
            text.setLength(0);
        }
    }

    private record Scan(Token token, int end) {}

    /**
     * 从 {@code start}（{@code '['}）试扫一个标签。
     *
     * @return {@code null}＝不是标签（{@code [} 退字面量）；token {@code null} 的
     *         Scan＝空名非闭（整段字面量）；否则开/闭标签
     */
    private static Scan scanTag(String s, int start) {
        int n = s.length();
        int j = start + 1;
        boolean closing = false;
        if (j < n && s.charAt(j) == '/') {
            closing = true;
            j++;
        }
        int nameStart = j;
        int nameEnd = -1;
        String attribute = null;
        while (true) {
            if (j >= n) {                            // 串尾未闭合仍算标签（parser quirk）
                nameEnd = n;
                break;
            }
            char c = s.charAt(j);
            if (c == ']') {
                nameEnd = j;
                j++;
                break;
            }
            if (c == '=' && !closing) {
                nameEnd = j;
                j++;
                int attrStart = j;
                while (j < n && s.charAt(j) != ']' && s.charAt(j) != '\n') {
                    j++;
                }
                attribute = s.substring(attrStart, j);
                if (j < n && s.charAt(j) == ']') {
                    j++;
                }
                break;
            }
            if (c == '\n' || c == '[') {             // 中止：整段不是标签
                return null;
            }
            j++;
        }
        var name = s.substring(nameStart, nameEnd).toLowerCase().trim();
        if (name.isEmpty() && !closing) {            // 空名非闭 ⇒ 整段字面量
            return new Scan(null, j);
        }
        var raw = s.substring(start, j);
        return new Scan(closing
            ? new Token.Close(raw, name)
            : new Token.Open(raw, name, attribute), j);
    }
}
