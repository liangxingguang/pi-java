package com.pijava.tui.component;

import java.util.regex.Pattern;

import com.pijava.tui.util.TextLayout;

/**
 * 行内 Markdown → TamboUI markup（批 25/docs/36 T4，pi markdown.ts:631-760
 * {@code renderInlineTokens} 的 java 对位子集）。
 *
 * <p>支持：反斜杠转义（ASCII 标点集，pi {@code escape} 臂）、codespan
 * （mdCode 色）、strong/em（{@code [bold]}/{@code [italic]}）、严格删除线
 * （{@link #STRICT_STRIKETHROUGH}——pi markdown.ts:7-24 正则语义逐字）、
 * 链接（OSC 8 臂：{@code [link=href]}＋underline＋mdLink 色，只显文本——
 * 裁决④ 实证：TamboUI {@code AnsiCellWriter} 经 {@code AbstractBackend} 吐
 * OSC 8 序列）。</p>
 *
 * <p>子集边界（窄差 D-new7）：不嵌套（强调/删除线内容按字面文本处理）、
 * 无 autolink/行内 html/图片行内形；{@code _} 走 CommonMark 内词守则
 * （两侧字母数字不开闭，snake_case 不误伤）。</p>
 */
final class InlineMarkup {

    /** pi markdown.ts:7 逐字（strict strikethrough tokenizer 的正则）。 */
    private static final Pattern STRICT_STRIKETHROUGH = Pattern.compile(
        "^(~~)(?=[^\\s~])((?:\\\\.|[^\\\\])*?(?:\\\\.|[^\\s~\\\\]))\\1(?=[^~]|$)");

    /** marked 反斜杠转义的 ASCII 标点集。 */
    private static final String ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private InlineMarkup() {}

    /**
     * 渲染一行内联 Markdown（不含换行）为 markup 串。
     *
     * @param text  源文本（单行）
     * @param theme 主题（codespan/链接色）
     * @return markup 串（字面量已转义，可直接进 {@code LogicalLine.markup}）
     */
    static String render(String text, MarkdownTheme theme) {
        var out = new StringBuilder(text.length() + 16);
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && ESCAPABLE.indexOf(text.charAt(i + 1)) >= 0) {
                out.append(TextLayout.escapeMarkup(String.valueOf(text.charAt(i + 1))));
                i += 2;
                continue;
            }
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {   // 空 codespan（``）不认
                    out.append(MarkdownTheme.tag(theme.code()))
                        .append(TextLayout.escapeMarkup(text.substring(i + 1, end)))
                        .append("[/]");
                    i = end + 1;
                    continue;
                }
            }
            if (c == '~' && i + 1 < n && text.charAt(i + 1) == '~') {
                var matcher = STRICT_STRIKETHROUGH.matcher(text.substring(i));
                if (matcher.find()) {
                    out.append("[s]")
                        .append(plainText(matcher.group(2)))
                        .append("[/]");
                    i += matcher.end();
                    continue;
                }
            }
            if (c == '*' || c == '_') {
                int consumed = tryEmphasis(text, i, c, out);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            if (c == '[') {
                int consumed = tryLink(text, i, theme, out);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            out.append(TextLayout.escapeMarkup(String.valueOf(c)));
            i++;
        }
        return out.toString();
    }

    /** 强调：{@code **}/{@code __} 优先于 {@code *}/{@code _}；flanking＋内词守则子集。 */
    private static int tryEmphasis(String text, int i, char marker, StringBuilder out) {
        int n = text.length();
        boolean dbl = i + 1 < n && text.charAt(i + 1) == marker;
        int mlen = dbl ? 2 : 1;
        int contentStart = i + mlen;
        if (contentStart >= n || Character.isWhitespace(text.charAt(contentStart))) {
            return 0;                                        // 开侧贴空白不认
        }
        if (marker == '_' && i > 0 && isWordChar(text.charAt(i - 1)) && isWordChar(text.charAt(contentStart))) {
            return 0;                                        // CommonMark 内词不开
        }
        var needle = dbl ? String.valueOf(new char[]{marker, marker}) : String.valueOf(marker);
        int close = contentStart;
        while (true) {
            close = text.indexOf(needle, close);
            if (close < 0) {
                return 0;
            }
            if (Character.isWhitespace(text.charAt(close - 1))) {
                close += mlen;                               // 闭侧贴空白不终
                continue;
            }
            if (marker == '_' && close + mlen < n && isWordChar(text.charAt(close + mlen))) {
                close += mlen;                               // 内词不闭
                continue;
            }
            break;
        }
        out.append(dbl ? "[bold]" : "[italic]")
            .append(plainText(text.substring(contentStart, close)))
            .append("[/]");
        return close + mlen - i;
    }

    /** 链接：{@code [text](href)}——OSC 8 臂（只显文本；href 含 ]/换行 ⇒ 退字面量）。 */
    private static int tryLink(String text, int i, MarkdownTheme theme, StringBuilder out) {
        int closeBracket = text.indexOf(']', i + 1);
        if (closeBracket < 0 || closeBracket == i + 1) {
            return 0;
        }
        int n = text.length();
        if (closeBracket + 1 >= n || text.charAt(closeBracket + 1) != '(') {
            return 0;
        }
        int closeParen = text.indexOf(')', closeBracket + 2);
        if (closeParen < 0) {
            return 0;
        }
        var label = text.substring(i + 1, closeBracket);
        var href = text.substring(closeBracket + 2, closeParen);
        if (href.isEmpty() || href.indexOf(']') >= 0 || href.indexOf('\n') >= 0) {
            return 0;                                        // 属性值装不下 ⇒ 字面量
        }
        // pi OSC 8 臂（markdown.ts:704-707）：styledLink = link(underline(text))，只显文本。
        out.append("[link=").append(href).append(']')
            .append("[u]").append(MarkdownTheme.tag(theme.link()))
            .append(plainText(label))
            .append("[/][/][/]");
        return closeParen + 1 - i;
    }

    /** 强调/删除线/链接内容的字面量路：解反斜杠转义＋markup 转义（不递归——窄差）。 */
    private static String plainText(String text) {
        var out = new StringBuilder(text.length() + 8);
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && ESCAPABLE.indexOf(text.charAt(i + 1)) >= 0) {
                out.append(TextLayout.escapeMarkup(String.valueOf(text.charAt(i + 1))));
                i += 2;
                continue;
            }
            out.append(TextLayout.escapeMarkup(String.valueOf(c)));
            i++;
        }
        return out.toString();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
