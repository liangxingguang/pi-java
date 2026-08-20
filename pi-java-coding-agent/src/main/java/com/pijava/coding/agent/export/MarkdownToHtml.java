package com.pijava.coding.agent.export;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 紧凑的 Markdown → HTML 渲染器（P6-12 HTML 导出用）。
 *
 * <p>支持块级：ATX 标题、围栏代码块（带语言类名）、无序/有序列表、引用、
 * 水平线、段落；行内：代码 span、粗体、斜体、删除线、链接。所有文本先做
 * HTML 转义，Markdown 结构在转义之后叠加，避免双重转义。行内代码 span 用
 * 拆分法隔离，避免占位符冲突。</p>
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

        for (var line : lines) {
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
        out.append("<pre><code");
        if (lang.length() > 0) {
            out.append(" class=\"language-").append(escape(lang.toString())).append('"');
        }
        out.append('>').append(escape(stripTrailingNewline(code.toString())))
            .append("</code></pre>\n");
        code.setLength(0);
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

    /** 在已转义文本上叠加 Markdown 行内结构。 */
    private static String markup(String text) {
        String result = LINK.matcher(text).replaceAll(m ->
            "<a href=\"" + m.group(2) + "\">" + m.group(1) + "</a>");
        result = BOLD.matcher(result).replaceAll("<strong>$1</strong>");
        result = ITALIC_STAR.matcher(result).replaceAll("<em>$1</em>");
        result = ITALIC_UNDER.matcher(result).replaceAll("<em>$1</em>");
        return STRIKE.matcher(result).replaceAll("<del>$1</del>");
    }

    /** HTML 转义（&、<、>、"）。 */
    static String escape(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
