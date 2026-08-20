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
        assertThat(html).contains("<pre><code class=\"language-java\">int x = 1 &lt; 2;")
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
}
