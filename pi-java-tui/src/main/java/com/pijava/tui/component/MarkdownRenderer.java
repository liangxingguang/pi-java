package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.MarkupTokens;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Style;

/**
 * Markdown → {@link LogicalLine} 排放器（批 25/docs/36 T3/T5；pi
 * {@code Markdown} 组件 markdown.ts:170-1024 的 java 对位子集）。
 *
 * <p>历史：本类原是 Markdown → Element 的渲染器（221 行死代码，B65），本批
 * 删除 Element 面（裁决⑦），改为聊天管线的宽度无感 LogicalLine 排放——换行
 * 由渲染期 {@link TextLayout#wrap} 承担。</p>
 *
 * <p>块后间距规则（pi {@code nextTokenType !== "space"} 对位）：源有空行（space
 * token）⇒ 一空行；无空行 ⇒ heading/code/quote/hr/table 补空行，paragraph 在
 * next≠list 时补，list 不补。</p>
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    private static final int HR_WIDTH = 80;   // pi min(width,80)——管线宽度无感（窄差 D-new6）
    private static final int LIST_DEPTH_INDENT = 4;   // pi "    ".repeat(depth)

    /**
     * Markdown → 逻辑行。
     *
     * @param markdown     源文本（{@code null}/空白 ⇒ 空表——pi 的 trim()=="" 臂）
     * @param theme        Markdown 主题（{@link MarkdownTheme#current()}）
     * @param defaultStyle pi 的 {@code defaultTextStyle} 对位（thinking 臂＝
     *                     thinkingText 色＋italic；助手文本臂＝{@link Style#EMPTY}）
     */
    public static List<LogicalLine> toLogicalLines(String markdown, MarkdownTheme theme,
                                                   Style defaultStyle) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        // pi 逐字：tab → 3 空格（lexing 前）
        var blocks = MarkdownBlocks.parse(markdown.replace("\t", "   "));
        var out = new ArrayList<LogicalLine>();
        for (int i = 0; i < blocks.size(); i++) {
            var spaced = blocks.get(i);
            emitBlock(spaced.block(), theme, defaultStyle, out);
            if (i + 1 >= blocks.size()) {
                break;
            }
            var next = blocks.get(i + 1).block();
            if (spaced.blankAfter()) {
                out.add(blank());                        // space token ⇒ ""
                continue;
            }
            switch (spaced.block()) {
                case MarkdownBlocks.Block.Paragraph p -> {
                    if (!(next instanceof MarkdownBlocks.Block.ListBlock)) {
                        out.add(blank());
                    }
                }
                case MarkdownBlocks.Block.ListBlock l -> { }   // list 不补（pi 同）
                default -> out.add(blank());
            }
        }
        return out;
    }

    private static LogicalLine blank() {
        return new LogicalLine("", 0, 0, false, Style.EMPTY);
    }

    private static void emitBlock(MarkdownBlocks.Block block, MarkdownTheme theme,
                                  Style defaultStyle, List<LogicalLine> out) {
        switch (block) {
            case MarkdownBlocks.Block.Heading h -> emitHeading(h, theme, defaultStyle, out);
            case MarkdownBlocks.Block.Paragraph p -> {
                // 软换行分行排放（pi 的 wrapTextWithAnsi 按 \n 分行对位）
                for (var line : p.text().split("\n", -1)) {
                    out.add(new LogicalLine(InlineMarkup.render(line, theme),
                        0, 0, false, defaultStyle));
                }
            }
            case MarkdownBlocks.Block.Code c -> emitCode(c, theme, out);
            case MarkdownBlocks.Block.ListBlock l -> emitList(l, theme, defaultStyle, out);
            case MarkdownBlocks.Block.Quote q -> emitQuote(q, theme, out);
            case MarkdownBlocks.Block.Image img -> out.add(new LogicalLine(
                "🖼 " + TextLayout.escapeMarkup(img.alt().isEmpty() ? img.url() : img.alt()),
                0, 0, false, Style.EMPTY.dim()));
            case MarkdownBlocks.Block.Table t -> emitTable(t, theme, defaultStyle, out);
            case MarkdownBlocks.Block.Hr hr -> out.add(new LogicalLine(
                "─".repeat(HR_WIDTH), 0, 0, true, fg(theme.hr())));
        }
    }

    // ── 标题（pi :473-498：L1 加下划线；L≥3 显 "#"*level+" " 前缀） ──

    private static void emitHeading(MarkdownBlocks.Block.Heading h, MarkdownTheme theme,
                                    Style defaultStyle, List<LogicalLine> out) {
        var style = fg(theme.heading()).bold();
        if (h.level() == 1) {
            style = style.underlined();
        }
        var prefix = h.level() >= 3 ? "#".repeat(h.level()) + " " : "";
        out.add(new LogicalLine(prefix + InlineMarkup.render(h.text(), theme),
            0, 0, false, style));
    }

    // ── 代码（pi :530-549：围栏行可见＋codeBlockIndent 缩进＋高亮门） ──

    private static void emitCode(MarkdownBlocks.Block.Code c, MarkdownTheme theme,
                                 List<LogicalLine> out) {
        var borderStyle = fg(theme.codeBlockBorder());
        out.add(new LogicalLine("```" + TextLayout.escapeMarkup(c.language()),
            0, 0, true, borderStyle));
        // 高亮门（裁决 D-new4）：lang 非空 ⇒ 语法高亮（pi 是 supportsLanguage 白名单）；
        // 否则整行 mdCodeBlock 色（pi theme.ts:1053-1055 对位——自动侦测不可靠）。
        boolean highlight = !c.language().isEmpty();
        var indent = theme.codeBlockIndent();
        for (var line : c.code().split("\n", -1)) {
            if (highlight) {
                out.add(new LogicalLine(indent + highlightLine(line), 0, 0, true, Style.EMPTY));
            } else {
                out.add(new LogicalLine(indent + TextLayout.escapeMarkup(line),
                    0, 0, true, fg(theme.codeBlock())));
            }
        }
        out.add(new LogicalLine("```", 0, 0, true, borderStyle));
    }

    /** SyntaxHighlighter 行内分段 → markup（{@code [#hex]…[/]}；文本转义）。 */
    static String highlightLine(String line) {
        var sb = new StringBuilder();
        for (var segment : SyntaxHighlighter.highlight(line, Style.EMPTY)) {
            var hex = segment.style().fg().map(c -> MarkdownTheme.toHex(c)).orElse(null);
            var text = TextLayout.escapeMarkup(segment.text());
            if (hex == null) {
                sb.append(text);
            } else {
                sb.append('[').append(hex).append(']').append(text).append("[/]");
            }
        }
        return sb.toString();
    }

    // ── 列表（pi :766-816：marker 整段 listBullet 色；悬垂缩进＝marker 显示宽） ──

    private static void emitList(MarkdownBlocks.Block.ListBlock l, MarkdownTheme theme,
                                 Style defaultStyle, List<LogicalLine> out) {
        for (var item : l.items()) {
            int base = item.depth() * LIST_DEPTH_INDENT;
            var marker = item.marker();
            var markup = MarkdownTheme.tag(theme.listBullet())
                + TextLayout.escapeMarkup(marker) + "[/]"
                + InlineMarkup.render(item.text(), theme);
            out.add(new LogicalLine(markup, base, base + marker.length(),
                false, defaultStyle));
        }
    }

    // ── 引用（pi :566-612："│ " 前缀 quoteBorder 色；内容 quote 色＋italic） ──

    private static void emitQuote(MarkdownBlocks.Block.Quote q, MarkdownTheme theme,
                                  List<LogicalLine> out) {
        var border = MarkdownTheme.tag(theme.quoteBorder()) + "│ [/]";
        var contentStyle = fg(theme.quote()).italic();
        for (var line : q.lines()) {
            out.add(new LogicalLine(border + InlineMarkup.render(line, theme),
                0, 2, false, contentStyle));
        }
    }

    // ── 表格（pi :852-1024 全边框；自然宽不收缩不 wrap——窄差 D-new3） ──

    private static void emitTable(MarkdownBlocks.Block.Table t, MarkdownTheme theme,
                                  Style defaultStyle, List<LogicalLine> out) {
        if (t.rows().isEmpty()) {
            return;
        }
        int cols = t.rows().stream().mapToInt(List::size).max().orElse(0);
        // 单元格先过行内渲染（pi 单元格走 renderInlineTokens）
        var rendered = t.rows().stream()
            .map(row -> row.stream().map(cell -> InlineMarkup.render(cell, theme)).toList())
            .toList();
        int[] widths = new int[cols];
        for (var row : rendered) {
            for (int c = 0; c < Math.min(cols, row.size()); c++) {
                widths[c] = Math.max(widths[c], MarkupTokens.displayWidth(row.get(c)));
            }
        }
        var top = border("┌", "┬", "┐", widths);
        var mid = border("├", "┼", "┤", widths);
        var bottom = border("└", "┴", "┘", widths);
        out.add(new LogicalLine(top, 0, 0, true, Style.EMPTY));
        for (int r = 0; r < rendered.size(); r++) {
            var row = rendered.get(r);
            var sb = new StringBuilder("│ ");
            for (int c = 0; c < cols; c++) {
                var cell = c < row.size() ? row.get(c) : "";
                sb.append(cell)
                    .append(" ".repeat(widths[c] - MarkupTokens.displayWidth(cell)));
                if (c < cols - 1) {
                    sb.append(" │ ");
                }
            }
            sb.append(" │");
            // 表头行 bold（pi theme.bold(padded) 对位）；行 style 作底、span 优先。
            out.add(new LogicalLine(sb.toString(), 0, 0, true,
                r == 0 ? Style.EMPTY.bold() : Style.EMPTY));
            // 表头后恒有分隔线；数据行间分隔（pi separatorLine 对位）。
            if (r == 0 || r < rendered.size() - 1) {
                out.add(new LogicalLine(mid, 0, 0, true, Style.EMPTY));
            }
        }
        out.add(new LogicalLine(bottom, 0, 0, true, Style.EMPTY));
    }

    /** pi 逐字形：{@code ┌─}＋单元格 {@code "─"*w} 以 {@code ─┬─} 相接＋{@code ─┐}。 */
    private static String border(String left, String junction, String right, int[] widths) {
        var cells = new ArrayList<String>(widths.length);
        for (int w : widths) {
            cells.add("─".repeat(w));
        }
        return left + "─" + String.join("─" + junction + "─", cells) + "─" + right;
    }

    private static Style fg(dev.tamboui.style.Color color) {
        return Style.create().fg(color);
    }
}
