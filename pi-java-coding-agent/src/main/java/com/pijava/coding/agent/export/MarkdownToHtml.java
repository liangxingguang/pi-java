package com.pijava.coding.agent.export;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 紧凑的 Markdown → HTML 渲染器（P6-12 HTML 导出用）。
 *
 * <p>支持块级：ATX 标题、围栏代码块（pi 对齐 {@code class="hljs"}）、GFM 表格、
 * 无序/有序列表、引用、水平线、段落；行内：代码 span、粗体、斜体、删除线、
 * 安全 scheme 的链接。所有文本先做 HTML 转义，Markdown 结构在转义之后叠加，
 * 避免双重转义。行内代码 span 用拆分法隔离，避免占位符冲突。</p>
 */
public final class MarkdownToHtml {

    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern ITALIC_UNDER = Pattern.compile("_([^_]+)_");
    private static final Pattern STRIKE = Pattern.compile("~~([^~]+)~~");
    private static final Pattern FENCE = Pattern.compile("^\\s*```(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UL_ITEM = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern OL_ITEM = Pattern.compile("^\\s*\\d+\\.\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern HR = Pattern.compile("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern TABLE_DELIMITER = Pattern.compile(
        "^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");
    private static final Pattern URL_SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Pattern ALLOWED_URL_SCHEME = Pattern.compile(
        "(?i)^(?:https?|mailto|tel|ftp)$");

    private MarkdownToHtml() {}

    /** 渲染完整 Markdown 文本为 HTML 片段。 */
    public static String render(String markdown) {
        var out = new StringBuilder();
        var lines = markdown.replace("\r\n", "\n").split("\n", -1);
        boolean inFence = false;
        var fenceLang = new StringBuilder();
        var fenceCode = new StringBuilder();
        var paragraph = new StringBuilder();
        String listKind = null;
        var listItems = new ArrayList<String>();

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            var line = lines[lineIndex];
            var fence = FENCE.matcher(line);
            if (fence.matches()) {
                if (!inFence) {
                    flushParagraph(out, paragraph);
                    flushList(out, listKind, listItems);
                    inFence = true;
                    fenceLang.setLength(0);
                    fenceLang.append(fence.group(1).trim());
                    fenceCode.setLength(0);
                } else {
                    emitCodeBlock(out, fenceCode, fenceLang);
                    inFence = false;
                }
                continue;
            }
            if (inFence) {
                fenceCode.append(line).append('\n');
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(out, paragraph);
                flushList(out, listKind, listItems);
                continue;
            }
            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(out, paragraph);
                flushList(out, listKind, listItems);
                int level = heading.group(1).length();
                out.append("<h").append(level).append('>')
                    .append(inline(heading.group(2).trim()))
                    .append("</h").append(level).append(">\n");
                continue;
            }
            if (HR.matcher(line).matches()) {
                flushParagraph(out, paragraph);
                flushList(out, listKind, listItems);
                out.append("<hr>\n");
                continue;
            }
            var quote = QUOTE.matcher(line);
            if (quote.matches()) {
                flushParagraph(out, paragraph);
                flushList(out, listKind, listItems);
                out.append("<blockquote>").append(inline(quote.group(1).trim()))
                    .append("</blockquote>\n");
                continue;
            }
            if (line.contains("|") && lineIndex + 1 < lines.length
                    && isTableDelimiter(lines[lineIndex + 1])) {
                flushParagraph(out, paragraph);
                flushList(out, listKind, listItems);
                lineIndex = emitTable(out, lines, lineIndex) - 1;
                continue;
            }
            var ul = UL_ITEM.matcher(line);
            if (ul.matches()) {
                flushParagraph(out, paragraph);
                if (listKind == null) {
                    listKind = "ul";
                } else if (!"ul".equals(listKind)) {
                    flushList(out, listKind, listItems);
                    listKind = "ul";
                }
                listItems.add(inline(ul.group(1).trim()));
                continue;
            }
            var ol = OL_ITEM.matcher(line);
            if (ol.matches()) {
                flushParagraph(out, paragraph);
                if (listKind == null) {
                    listKind = "ol";
                } else if (!"ol".equals(listKind)) {
                    flushList(out, listKind, listItems);
                    listKind = "ol";
                }
                listItems.add(inline(ol.group(1).trim()));
                continue;
            }
            flushList(out, listKind, listItems);
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(line.trim());
        }
        flushParagraph(out, paragraph);
        flushList(out, listKind, listItems);
        if (inFence) {
            emitCodeBlock(out, fenceCode, fenceLang);
        }
        return out.toString();
    }

    /** GFM 表格：表头取首行，后续连续含管道的行为表体，delimiter 行跳过。 */
    private static int emitTable(StringBuilder out, String[] lines, int start) {
        var header = splitCells(lines[start]);
        var rows = new ArrayList<List<String>>();
        int i = start + 2;
        for (; i < lines.length && lines[i].contains("|"); i++) {
            if (isTableDelimiter(lines[i])) {
                continue;
            }
            rows.add(splitCells(lines[i]));
        }
        out.append("<table>\n<thead><tr>");
        for (var cell : header) {
            out.append("<th>").append(inline(cell)).append("</th>");
        }
        out.append("</tr></thead>\n<tbody>");
        for (var row : rows) {
            out.append("<tr>");
            for (var cell : row) {
                out.append("<td>").append(inline(cell)).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</tbody>\n</table>\n");
        return i;
    }

    private static List<String> splitCells(String line) {
        var trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        var cells = new ArrayList<String>();
        for (var cell : trimmed.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static boolean isTableDelimiter(String line) {
        return TABLE_DELIMITER.matcher(line).matches();
    }

    private static void flushParagraph(StringBuilder out, StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        out.append("<p>").append(inline(paragraph.toString())).append("</p>\n");
        paragraph.setLength(0);
    }

    private static void flushList(StringBuilder out, String kind, List<String> items) {
        if (kind == null) {
            return;
        }
        var tag = "ol".equals(kind) ? "ol" : "ul";
        out.append('<').append(tag).append(">\n");
        for (var item : items) {
            out.append("  <li>").append(item).append("</li>\n");
        }
        out.append("</").append(tag).append(">\n");
        items.clear();
    }

    private static void emitCodeBlock(StringBuilder out, StringBuilder code, StringBuilder lang) {
        out.append("<pre><code class=\"hljs\">")
            .append(escape(stripTrailingNewline(code.toString())))
            .append("</code></pre>\n");
        code.setLength(0);
        lang.setLength(0);
    }

    private static String stripTrailingNewline(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * 行内渲染：以代码 span 为界拆分，先转义整段，再对非代码段叠加
     * 链接/粗体/斜体/删除线。
     */
    static String inline(String text) {
        String escaped = escape(text);
        var out = new StringBuilder();
        var parts = CODE_SPAN.split(escaped, -1);
        var matcher = CODE_SPAN.matcher(escaped);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                matcher.find();
                out.append("<code>").append(matcher.group(1)).append("</code>");
            }
            out.append(markup(parts[i]));
        }
        return out.toString();
    }

    /**
     * 在已转义文本上叠加 Markdown 行内结构。
     *
     * <p>L5 W8（docs/25 §9；pi {@code marked-cjk-friendly}，D225 的 Java 等价臂）：
     * 强调定界符紧贴 CJK 全角标点时放宽 flanking——{@code **中文。**} 里收尾
     * {@code **} 内侧是 {@code 。}（全角标点），即使外侧紧跟字母也照样闭合；
     * 纯拉丁文本（{@code **end.**Then}）仍按 CommonMark 不成立。</p>
     */
    private static String markup(String text) {
        String result = LINK.matcher(text).replaceAll(m -> {
            String href = sanitizeHref(m.group(2));
            return href == null ? m.group(1)
                : "<a href=\"" + href + "\">" + m.group(1) + "</a>";
        });
        result = emphasize(result, BOLD, "<strong>", "</strong>");
        result = emphasize(result, ITALIC_STAR, "<em>", "</em>");
        result = emphasize(result, ITALIC_UNDER, "<em>", "</em>");
        return STRIKE.matcher(result).replaceAll("<del>$1</del>");
    }

    /** pi {@code sanitizeMarkdownUrl} 对位：剔除控制符并按 scheme 白名单过滤。 */
    static String sanitizeHref(String value) {
        String href = value == null ? "" : value.trim().replaceAll("[\\x00-\\x1f\\x7f]", "");
        if (href.isEmpty()) {
            return href;
        }
        var scheme = URL_SCHEME.matcher(href);
        if (scheme.find() && !ALLOWED_URL_SCHEME.matcher(scheme.group(1)).matches()) {
            return null;
        }
        return href;
    }

    /**
     * 逐个替换 {@code pattern} 的命中，闭合侧按 CJK 规则放行：
     * 闭合定界符紧邻的<b>内侧</b>字符是 CJK 字符/全角标点 ⇒ 允许；
     * 否则要求闭合定界符外侧不是字母或数字（CommonMark 的 right-flanking）。
     */
    private static String emphasize(String text, Pattern pattern, String open, String close) {
        var matcher = pattern.matcher(text);
        var out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            var inner = matcher.group(1);
            int closeLength = (matcher.end() - matcher.start() - inner.length()) / 2;
            int afterIndex = matcher.end();
            // 收尾定界符的内侧字符（强调内容的最后一字）与外侧字符。
            var innerLastChar = inner.isEmpty() ? " "
                : new String(Character.toChars(inner.codePointBefore(inner.length())));
            var after = afterIndex >= text.length() ? " "
                : new String(Character.toChars(text.codePointAt(afterIndex)));
            boolean closes = isCjk(innerLastChar) || isCjk(after)
                || !isWordChar(after.codePointAt(0));
            if (!closes || closeLength == 0) {
                continue;
            }
            out.append(text, last, matcher.start()).append(open)
                .append(inner).append(close);
            last = matcher.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static boolean isWordChar(int codePoint) {
        return Character.isLetterOrDigit(codePoint);
    }

    /** CJK 字符与全角标点（pi 的 {@code isCjkFriendly} 判定范围）。 */
    static boolean isCjk(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int codePoint = value.codePointAt(0);
        if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.KATAKANA
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL) {
            return true;
        }
        // 全角形式与「CJK 符号和标点」块（：。、！？「」etc.）。
        return (codePoint >= 0xFF01 && codePoint <= 0xFF60)
            || (codePoint >= 0x3000 && codePoint <= 0x303F);
    }

    /** HTML 转义（&、<、>、"）。 */
    static String escape(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
