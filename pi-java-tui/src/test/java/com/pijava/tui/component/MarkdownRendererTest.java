package com.pijava.tui.component;

import java.util.List;

import com.pijava.tui.component.MarkdownBlocks.Block;

import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 25（docs/36 T3/T5）：块 parser＋LogicalLine 排放。
 */
class MarkdownRendererTest {

    private static final MarkdownTheme THEME = new MarkdownTheme(
        dev.tamboui.style.Color.hex("#FFFF00"), // heading
        dev.tamboui.style.Color.hex("#0000FF"), // link
        dev.tamboui.style.Color.hex("#888888"), // linkUrl
        dev.tamboui.style.Color.hex("#FF00FF"), // code
        dev.tamboui.style.Color.hex("#00FF00"), // codeBlock
        dev.tamboui.style.Color.hex("#777777"), // codeBlockBorder
        dev.tamboui.style.Color.hex("#666666"), // quote
        dev.tamboui.style.Color.hex("#555555"), // quoteBorder
        dev.tamboui.style.Color.hex("#444444"), // hr
        dev.tamboui.style.Color.hex("#FF00FF"), // listBullet
        "  ");

    private static List<LogicalLine> lines(String markdown) {
        return MarkdownRenderer.toLogicalLines(markdown, THEME, Style.EMPTY);
    }

    // ── 块 parser ─────────────────────────────────────────

    @Test
    void parsesHeadingsCodeListsAndQuotes() {
        var blocks = MarkdownBlocks.parseBlocks("""
            # Title
            plain paragraph

            ```java
            class A {}
            ```
            - one
            - two
            > a quote
            """);

        assertThat(blocks.get(0)).isEqualTo(new Block.Heading(1, "Title"));
        assertThat(blocks.get(1)).isEqualTo(new Block.Paragraph("plain paragraph"));
        assertThat(blocks.get(2)).isEqualTo(new Block.Code("java", "class A {}"));
        assertThat(blocks.get(3)).isInstanceOf(Block.ListBlock.class);
        assertThat(blocks.get(4)).isEqualTo(new Block.Quote(List.of("a quote")));
    }

    @Test
    void headingLevelsOneToSix() {
        var blocks = MarkdownBlocks.parseBlocks("## two\n###### six\n#no space");
        assertThat(blocks.get(0)).isEqualTo(new Block.Heading(2, "two"));
        assertThat(blocks.get(1)).isEqualTo(new Block.Heading(6, "six"));
        // 无空格 ⇒ 非标题（CommonMark 要求 # 后空格）
        assertThat(blocks.get(2)).isInstanceOf(Block.Paragraph.class);
    }

    @Test
    void paragraphMergesConsecutiveLines() {
        var blocks = MarkdownBlocks.parseBlocks("line one\nline two\n\nnext");
        assertThat(blocks.get(0)).isEqualTo(new Block.Paragraph("line one\nline two"));
        assertThat(blocks.get(1)).isEqualTo(new Block.Paragraph("next"));
    }

    @Test
    void paragraphInterruptedByBlockStarts() {
        var blocks = MarkdownBlocks.parseBlocks("text\n- item\nmore");
        assertThat(blocks.get(0)).isEqualTo(new Block.Paragraph("text"));
        assertThat(blocks.get(1)).isInstanceOf(Block.ListBlock.class);
        assertThat(blocks.get(2)).isEqualTo(new Block.Paragraph("more"));
    }

    @Test
    void orderedTaskAndNestedListItems() {
        var blocks = MarkdownBlocks.parseBlocks("""
            1. first
            2. second
            - [x] done
            - [ ] todo
              - nested
            """);
        var list = (Block.ListBlock) blocks.get(0);
        assertThat(list.items()).containsExactly(
            new MarkdownBlocks.Item(0, "1. ", "first"),
            new MarkdownBlocks.Item(0, "2. ", "second"),
            new MarkdownBlocks.Item(0, "- [x] ", "done"),
            new MarkdownBlocks.Item(0, "- [ ] ", "todo"),
            new MarkdownBlocks.Item(1, "- ", "nested"));
    }

    @Test
    void hrThreeForms() {
        var blocks = MarkdownBlocks.parseBlocks("---\n***\n___\ntext");
        assertThat(blocks.get(0)).isInstanceOf(Block.Hr.class);
        assertThat(blocks.get(1)).isInstanceOf(Block.Hr.class);
        assertThat(blocks.get(2)).isInstanceOf(Block.Hr.class);
        assertThat(blocks.get(3)).isInstanceOf(Block.Paragraph.class);
    }

    @Test
    void tildeFenceCodeBlock() {
        var blocks = MarkdownBlocks.parseBlocks("~~~js\nx = 1\n~~~");
        assertThat(blocks.get(0)).isEqualTo(new Block.Code("js", "x = 1"));
    }

    @Test
    void parsesPipeTables() {
        var blocks = MarkdownBlocks.parseBlocks("""
            | a | b |
            |---|---|
            | 1 | 2 |
            """);
        assertThat(blocks).hasSize(1);
        assertThat(((Block.Table) blocks.get(0)).rows())
            .containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    void parsesImageBlocks() {
        var blocks = MarkdownBlocks.parseBlocks("![diagram](https://ex.com/d.png)");
        assertThat(blocks.get(0)).isEqualTo(new Block.Image("diagram", "https://ex.com/d.png"));
    }

    // ── R5 流式局部围栏修剪（pi markdown.ts:146-169） ─────────

    @Test
    void trimsPartialClosingFenceSingleBacktick() {
        var blocks = MarkdownBlocks.parseBlocks("```\ncode\n`");
        assertThat(((Block.Code) blocks.get(0)).code()).isEqualTo("code");
    }

    @Test
    void trimsPartialClosingFenceDoubleBacktick() {
        var blocks = MarkdownBlocks.parseBlocks("```\ncode\n``");
        assertThat(((Block.Code) blocks.get(0)).code()).isEqualTo("code");
    }

    @Test
    void closedFenceKeepsContentVerbatim() {
        // 完整闭合 ⇒ 不修剪（内容尾行 `` 保留——pi 的 lastLine>=marker.length 臂）
        var blocks = MarkdownBlocks.parseBlocks("```\ncode\n``\n```");
        assertThat(((Block.Code) blocks.get(0)).code()).isEqualTo("code\n``");
    }

    @Test
    void unclosedFenceWithRealContentTailUntouched() {
        var blocks = MarkdownBlocks.parseBlocks("```\nline one\nline two");
        assertThat(((Block.Code) blocks.get(0)).code()).isEqualTo("line one\nline two");
    }

    // ── 排放 golden ───────────────────────────────────────

    @Test
    void codeBlockEmitsVisibleFencesAndIndent() {
        var out = lines("```java\nint x = 1;\n```\n");
        assertThat(out).hasSize(3);
        assertThat(out.get(0).markup()).isEqualTo("```java");
        assertThat(out.get(0).preformatted()).isTrue();
        // lang 非空 ⇒ 高亮臂（关键字 int 上色）
        assertThat(out.get(1).markup()).startsWith("  [#");
        assertThat(out.get(1).markup()).contains("int[/]");
        assertThat(out.get(2).markup()).isEqualTo("```");
    }

    @Test
    void plainCodeBlockUsesCodeBlockColorWithoutTags() {
        var out = lines("```\nplain [text]\n```");
        // 无 lang ⇒ 整行 mdCodeBlock 色（行 style），内容转义无标签
        assertThat(out.get(1).markup()).isEqualTo("  plain [[text]]");
        assertThat(out.get(1).style().fg()).isPresent();
    }

    @Test
    void headingLevel3ShowsHashPrefix() {
        var out = lines("### sub");
        assertThat(out.get(0).markup()).isEqualTo("### sub");
        assertThat(out.get(0).style().fg()).isPresent();
        assertThat(out.get(0).style().addModifiers())
            .contains(dev.tamboui.style.Modifier.BOLD);
    }

    @Test
    void blockSpacingRules() {
        // heading 后无空行 ⇒ 补空行；paragraph 后是 list ⇒ 不补
        var out = lines("# t\ntext\n- item");
        assertThat(out.stream().map(LogicalLine::markup).toList())
            .containsExactly("t", "", "text", "[#FF00FF]- [/]item");
    }

    @Test
    void blankSourceLineCountsAsSpaceToken() {
        var out = lines("para one\n\npara two");
        assertThat(out.stream().map(LogicalLine::markup).toList())
            .containsExactly("para one", "", "para two");
    }

    @Test
    void listHangingIndent() {
        var out = lines("- [x] done task");
        assertThat(out.get(0).initialIndent()).isZero();
        assertThat(out.get(0).subsequentIndent()).isEqualTo("- [x] ".length());
        // 任务标记里的 [x] 是字面文本 ⇒ markup 转义（否则被当标签吞掉）
        assertThat(out.get(0).markup()).isEqualTo("[#FF00FF]- [[x]] [/]done task");
    }

    @Test
    void nestedListIndentsByFourPerDepth() {
        var out = lines("- top\n  - nested");
        assertThat(out.get(1).initialIndent()).isEqualTo(4);
        assertThat(out.get(1).subsequentIndent()).isEqualTo(6);
    }

    @Test
    void quoteEmitsBorderPrefixAndItalicStyle() {
        var out = lines("> quoted");
        assertThat(out.get(0).markup()).startsWith("[#555555]│ [/]");
        assertThat(out.get(0).markup()).endsWith("quoted");
        assertThat(out.get(0).style().addModifiers())
            .contains(dev.tamboui.style.Modifier.ITALIC);
    }

    @Test
    void tableEmitsFullBordersWithNaturalWidths() {
        var out = lines("| a | bb |\n|---|---|\n| 1 | 22 |");
        assertThat(out.stream().map(LogicalLine::markup).toList())
            .containsExactly(
                "┌───┬────┐",
                "│ a │ bb │",
                "├───┼────┤",
                "│ 1 │ 22 │",
                "└───┴────┘");
        // 表头行 bold；全表 preformatted（自然宽不收缩，窄差 D-new3）
        assertThat(out.get(1).style().addModifiers())
            .contains(dev.tamboui.style.Modifier.BOLD);
        assertThat(out).allMatch(LogicalLine::preformatted);
    }

    @Test
    void hrIsFixedWidthPreformatted() {
        var out = lines("---");
        assertThat(out.get(0).markup()).isEqualTo("─".repeat(80));
        assertThat(out.get(0).preformatted()).isTrue();
    }

    @Test
    void mermaidFenceRendersAsPlainCodeBlock() {
        // 裁决⑩：mermaid 围栏按普通代码块（pi 无 transformer 时同形）
        var out = lines("```mermaid\ngraph TD\n```");
        assertThat(out.get(0).markup()).isEqualTo("```mermaid");
        assertThat(out.get(1).markup()).isEqualTo("  graph TD");
    }

    @Test
    void tabsNormalizeToThreeSpaces() {
        var out = lines("a\tb");
        assertThat(out.get(0).markup()).isEqualTo("a   b");
    }

    @Test
    void blankInputProducesNoLines() {
        assertThat(lines("")).isEmpty();
        assertThat(lines("   \n  ")).isEmpty();
    }

    // ── 高亮行序列化 ──────────────────────────────────────

    @Test
    void highlightLineEscapesBracketsInCode() {
        var markup = MarkdownRenderer.highlightLine("var a = b[0];");
        // [ → [[，] → ]]（markup 转义），避免 a[0] 被误解析为标签
        assertThat(markup).doesNotContain("b[0]");
        assertThat(markup).contains("b[[");
    }
}
