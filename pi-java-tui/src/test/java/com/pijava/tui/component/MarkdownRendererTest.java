package com.pijava.tui.component;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: Markdown block parsing (headings/code/lists/quotes).
 */
class MarkdownRendererTest {

    @Test
    void parsesHeadingsCodeListsAndQuotes() {
        var markdown = """
            # Title
            plain paragraph

            ```java
            class A {}
            ```
            - one
            - two
            > a quote
            """;

        var blocks = MarkdownRenderer.parseBlocks(markdown);

        assertThat(blocks.get(0)).isInstanceOf(MarkdownRenderer.Block.Heading.class);
        assertThat(blocks.get(1)).isInstanceOf(MarkdownRenderer.Block.Paragraph.class);
        assertThat(blocks.get(2)).isInstanceOf(MarkdownRenderer.Block.Code.class);
        assertThat(((MarkdownRenderer.Block.Code) blocks.get(2)).code())
            .isEqualTo("class A {}");
        assertThat(blocks.get(3)).isInstanceOf(MarkdownRenderer.Block.ListBlock.class);
        assertThat(((MarkdownRenderer.Block.ListBlock) blocks.get(3)).items())
            .containsExactly("one", "two");
        assertThat(blocks.get(4)).isInstanceOf(MarkdownRenderer.Block.Quote.class);
    }

    @Test
    void rendersWithoutException() {
        var renderer = new MarkdownRenderer();
        assertThat(renderer.render("# Hi\n\n```\ncode\n```\n- a\n> q")).isNotNull();
    }

    @Test
    void parsesPipeTables() {
        var markdown = """
            | a | b |
            |---|---|
            | 1 | 2 |
            """;

        var blocks = MarkdownRenderer.parseBlocks(markdown);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(MarkdownRenderer.Block.Table.class);
        assertThat(((MarkdownRenderer.Block.Table) blocks.get(0)).rows())
            .containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    void parsesImageBlocks() {
        var blocks = MarkdownRenderer.parseBlocks("![diagram](https://ex.com/d.png)");

        assertThat(blocks.get(0)).isInstanceOf(MarkdownRenderer.Block.Image.class);
        assertThat(((MarkdownRenderer.Block.Image) blocks.get(0)).alt())
            .isEqualTo("diagram");
        assertThat(((MarkdownRenderer.Block.Image) blocks.get(0)).url())
            .isEqualTo("https://ex.com/d.png");
    }

    @Test
    void mermaidFenceIsCodeBlockWithLanguage() {
        var blocks = MarkdownRenderer.parseBlocks("```mermaid\ngraph TD\nA-->B\n```");

        assertThat(blocks.get(0)).isInstanceOf(MarkdownRenderer.Block.Code.class);
        assertThat(((MarkdownRenderer.Block.Code) blocks.get(0)).language())
            .isEqualTo("mermaid");
    }

    @Test
    void highlightLineEmitsColorMarkupForKeywordsAndNumbers() {
        var markup = MarkdownRenderer.highlightLine("public int x = 42;");
        // 关键字 public/int → #569CD6，数字 42 → #B5CEA8
        assertThat(markup).contains("[#569CD6]public[/]")
            .contains("[#569CD6]int[/]")
            .contains("[#B5CEA8]42[/]");
    }

    @Test
    void highlightLineEscapesBracketsInCode() {
        var markup = MarkdownRenderer.highlightLine("var a = b[0];");
        // [ → [[，] → ]]（markup 转义），避免 a[0] 被误解析为标签
        assertThat(markup).doesNotContain("b[0]");
        assertThat(markup).contains("b[[");
        assertThat(markup).contains("]]");
    }
}
