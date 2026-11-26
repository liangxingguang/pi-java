package com.pijava.coding.agent.export;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-12: MarkdownToHtml — 块级/行内 Markdown 渲染与 HTML 转义。
 */
class MarkdownToHtmlTest {

    @Test
    void rendersHeadingsAndParagraphs() {
        var html = MarkdownToHtml.render("# Title\n\nA paragraph.");
        assertThat(html).contains("<h1>Title</h1>")
            .contains("<p>A paragraph.</p>");
    }

    @Test
    void rendersFencedCodeBlockWithLanguage() {
        var html = MarkdownToHtml.render("```java\nint x = 1 < 2;\n```");
        assertThat(html).contains("<pre><code class=\"hljs\">int x = 1 &lt; 2;")
            .doesNotContain("language-java")
            .contains("</code></pre>");
    }

    @Test
    void escapesHtmlInText() {
        var html = MarkdownToHtml.render("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;")
            .doesNotContain("<script>");
    }

    @Test
    void inlineCodeIsEscapedButNotDoubleEscaped() {
        var html = MarkdownToHtml.render("use `a < b` here");
        assertThat(html).contains("<code>a &lt; b</code>");
    }

    @Test
    void rendersBoldItalicAndLink() {
        var html = MarkdownToHtml.render("**bold** and *italic* and [link](https://x.dev)");
        assertThat(html).contains("<strong>bold</strong>")
            .contains("<em>italic</em>")
            .contains("<a href=\"https://x.dev\">link</a>");
    }

    @Test
    void rendersLists() {
        var html = MarkdownToHtml.render("- one\n- two\n\n1. first\n2. second");
        assertThat(html).contains("<ul>").contains("<li>one</li>").contains("<li>two</li>");
        assertThat(html).contains("<ol>").contains("<li>first</li>");
    }

    @Test
    void rendersGfmTable() {
        var html = MarkdownToHtml.render("| A | B |\n|---|---|\n| 1 | 2 |");
        assertThat(html).contains("<table>")
            .contains("<thead><tr><th>A</th><th>B</th></tr></thead>")
            .contains("<tbody><tr><td>1</td><td>2</td></tr></tbody>")
            .contains("</table>");
    }

    @Test
    void tableCellsRenderInlineMarkup() {
        var html = MarkdownToHtml.render("| A | B |\n|---|---|\n| **bold** | `code` |");
        assertThat(html).contains("<th>A</th>")
            .contains("<td><strong>bold</strong></td>")
            .contains("<td><code>code</code></td>");
    }

    @Test
    void blocksDangerousLinkSchemes() {
        var html = MarkdownToHtml.render("[x](javascript:alert(1)) [y](data:text/html,x)");
        assertThat(html).doesNotContain("<a")
            .contains("x")
            .contains("y");
    }

    @Test
    void allowsSafeSchemesAndRelativePaths() {
        var html = MarkdownToHtml.render("[a](https://x.dev) [b](http://x.dev) "
            + "[c](mailto:a@b.dev) [d](tel:123) [e](ftp://x.dev) [f](docs/a.md)");
        assertThat(html).contains("<a href=\"https://x.dev\">a</a>")
            .contains("<a href=\"http://x.dev\">b</a>")
            .contains("<a href=\"mailto:a@b.dev\">c</a>")
            .contains("<a href=\"tel:123\">d</a>")
            .contains("<a href=\"ftp://x.dev\">e</a>")
            .contains("<a href=\"docs/a.md\">f</a>");
    }
}
