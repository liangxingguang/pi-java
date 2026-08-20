package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;

/**
 * Lightweight Markdown → widget conversion (Phase 3 design §5.1).
 *
 * <p>Phase 3 supports headings, bold/italic markup, fenced code blocks,
 * bullet lists and quotes with a hand-rolled line parser; Phase 6 (P6-22)
 * adds pipe tables, image placeholders and mermaid diagram blocks.</p>
 */
public final class MarkdownRenderer {

    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|[\\s:|-]+\\|?\\s*$");

    /** Render markdown text into a widget column. */
    public Element render(String markdown) {
        return TamboUIAdapter.column(parseBlocks(markdown).stream()
            .map(this::renderBlock)
            .toList());
    }

    private Element renderBlock(Block block) {
        return switch (block) {
            case Block.Heading(int level, String text) ->
                TamboUIAdapter.text(text).bold();
            case Block.Paragraph(String text) ->
                TamboUIAdapter.markupText(text);
            case Block.Code(String lang, String code) ->
                "mermaid".equals(lang)
                    ? TamboUIAdapter.panel(TamboUIAdapter.text("[mermaid diagram]\n" + code))
                        .gray().rounded()
                    : TamboUIAdapter.panel(TamboUIAdapter.text(code)).gray().rounded();
            case Block.ListBlock(List<String> items) ->
                TamboUIAdapter.column(items.stream()
                    .map(item -> TamboUIAdapter.text("" + (char) 0x2022 + " " + item))
                    .toList());
            case Block.Quote(String text) ->
                TamboUIAdapter.text(text).dim();
            case Block.Image(String alt, String url) ->
                TamboUIAdapter.text("" + (char) 0x1F5BC + " " + (alt.isEmpty() ? url : alt)).dim();
            case Block.Table(List<List<String>> rows) -> renderTable(rows);
        };
    }

    /** 渲染管道表格：按列宽对齐，边框用盒绘制字符。 */
    private Element renderTable(List<List<String>> rows) {
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[cols];
        for (var row : rows) {
            for (int c = 0; c < Math.min(cols, row.size()); c++) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }
        var elements = new ArrayList<Element>();
        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            var sb = new StringBuilder((char) 0x2502 + " ");
            for (int c = 0; c < cols; c++) {
                var cell = c < row.size() ? row.get(c) : "";
                sb.append(cell).append(" ".repeat(widths[c] - cell.length()))
                    .append(" ").append((char) 0x2502).append(" ");
            }
            elements.add(TamboUIAdapter.text(sb.toString()));
            if (r == 0) {
                var sep = new StringBuilder((char) 0x251C);
                for (int c = 0; c < cols; c++) {
                    sep.append(String.valueOf((char) 0x2500).repeat(widths[c] + 2));
                    sep.append(c < cols - 1 ? (char) 0x2534 : (char) 0x2524);
                }
                elements.add(TamboUIAdapter.text(sep.toString()));
            }
        }
        return TamboUIAdapter.column(elements);
    }

    /** Parse markdown into a block list (line-based). */
    static List<Block> parseBlocks(String markdown) {
        var blocks = new ArrayList<Block>();
        var lines = markdown.split("\r?\n", -1);
        var index = 0;
        while (index < lines.length) {
            var line = lines[index];
            if (line.isBlank()) {
                index++;
                continue;
            }
            if (line.startsWith("```")) {
                var lang = line.substring(3).trim();
                var code = new StringBuilder();
                index++;
                while (index < lines.length && !lines[index].startsWith("```")) {
                    code.append(lines[index]).append('\n');
                    index++;
                }
                index++; // closing fence
                blocks.add(new Block.Code(lang, code.toString().stripTrailing()));
                continue;
            }
            if (isTableStart(lines, index)) {
                var rows = new ArrayList<List<String>>();
                rows.add(parseTableRow(lines[index]));
                index += 2; // header + separator
                while (index < lines.length && lines[index].trim().startsWith("|")) {
                    rows.add(parseTableRow(lines[index]));
                    index++;
                }
                blocks.add(new Block.Table(rows));
                continue;
            }
            var image = IMAGE.matcher(line);
            if (image.matches()) {
                blocks.add(new Block.Image(image.group(1), image.group(2)));
                index++;
                continue;
            }
            if (line.startsWith("### ")) {
                blocks.add(new Block.Heading(3, line.substring(4)));
            } else if (line.startsWith("## ")) {
                blocks.add(new Block.Heading(2, line.substring(3)));
            } else if (line.startsWith("# ")) {
                blocks.add(new Block.Heading(1, line.substring(2)));
            } else if (line.startsWith("> ")) {
                blocks.add(new Block.Quote(line.substring(2)));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                var items = new ArrayList<String>();
                while (index < lines.length
                        && (lines[index].startsWith("- ")
                            || lines[index].startsWith("* "))) {
                    items.add(lines[index].substring(2));
                    index++;
                }
                blocks.add(new Block.ListBlock(items));
                continue;
            } else {
                blocks.add(new Block.Paragraph(line));
            }
            index++;
        }
        return blocks;
    }

    /** 判断是否为管道表格开头（当前行 + 下一行分隔线）。 */
    private static boolean isTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length || !lines[index].trim().startsWith("|")) {
            return false;
        }
        var separator = lines[index + 1].trim();
        return TABLE_SEPARATOR.matcher(separator).matches()
            && separator.chars().filter(c -> c == '-').count() > 0;
    }

    /** 解析单行管道表格：去掉首尾 | 后按 | 切分并 trim。 */
    private static List<String> parseTableRow(String line) {
        var content = line.trim();
        if (content.startsWith("|")) {
            content = content.substring(1);
        }
        if (content.endsWith("|")) {
            content = content.substring(0, content.length() - 1);
        }
        return Arrays.stream(content.split("\\|")).map(String::trim).toList();
    }

    /** Parsed markdown block (internal). */
    sealed interface Block {
        record Heading(int level, String text) implements Block {}
        record Paragraph(String text) implements Block {}
        record Code(String language, String code) implements Block {}
        record ListBlock(List<String> items) implements Block {}
        record Quote(String text) implements Block {}
        record Image(String alt, String url) implements Block {}
        record Table(List<List<String>> rows) implements Block {}
    }
}
