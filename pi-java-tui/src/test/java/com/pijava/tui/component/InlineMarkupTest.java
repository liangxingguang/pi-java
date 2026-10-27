package com.pijava.tui.component;

import dev.tamboui.buffer.Cell;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.AnsiCellWriter;
import dev.tamboui.text.MarkupParser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 25（docs/36 T4＋裁决④）：行内 Markdown → markup；OSC 8 实证。
 */
class InlineMarkupTest {

    private static final MarkdownTheme THEME = new MarkdownTheme(
        dev.tamboui.style.Color.hex("#FFFF00"), dev.tamboui.style.Color.hex("#0000FF"),
        dev.tamboui.style.Color.hex("#888888"), dev.tamboui.style.Color.hex("#FF00FF"),
        dev.tamboui.style.Color.hex("#00FF00"), dev.tamboui.style.Color.hex("#777777"),
        dev.tamboui.style.Color.hex("#666666"), dev.tamboui.style.Color.hex("#555555"),
        dev.tamboui.style.Color.hex("#444444"), dev.tamboui.style.Color.hex("#FF00FF"),
        "  ");

    private static String render(String text) {
        return InlineMarkup.render(text, THEME);
    }

    @Test
    void plainTextIsMarkupEscaped() {
        assertThat(render("a[0] \\ b")).isEqualTo("a[[0]] \\\\ b");
    }

    @Test
    void boldItalicAndCodespan() {
        assertThat(render("**b**")).isEqualTo("[bold]b[/]");
        assertThat(render("*i*")).isEqualTo("[italic]i[/]");
        assertThat(render("`x = 1`")).isEqualTo("[#FF00FF]x = 1[/]");
    }

    @Test
    void emphasisContentIsLiteralSubset() {
        // 窄差 D-new7：强调内容不递归——内含标记按字面
        assertThat(render("**a *b* c**")).isEqualTo("[bold]a *b* c[/]");
    }

    @Test
    void underscoreIntraWordDoesNotEmphasize() {
        // CommonMark 内词守则：snake_case 不误伤
        assertThat(render("foo_bar_baz")).isEqualTo("foo_bar_baz");
        assertThat(render("_ok_")).isEqualTo("[italic]ok[/]");
    }

    @Test
    void strictStrikethrough() {
        assertThat(render("~~gone~~")).isEqualTo("[s]gone[/]");
        // 严格删除线（pi markdown.ts:7）：开侧贴空白不认
        assertThat(render("~~ a ~~")).isEqualTo("~~ a ~~");
        assertThat(render("~~a~~b~~")).isEqualTo("[s]a[/]b~~");
    }

    @Test
    void backslashEscapesResolve() {
        assertThat(render("a\\*b")).isEqualTo("a*b");
        assertThat(render("\\[x\\]")).isEqualTo("[[x]]");
    }

    @Test
    void linkEmitsOsc8TagUnderlineAndLinkColor() {
        // 裁决④ OSC 8 臂（pi markdown.ts:704-707）：只显文本，不附 (href)
        assertThat(render("[pi](https://e.com)"))
            .isEqualTo("[link=https://e.com][u][#0000FF]pi[/][/][/]");
    }

    @Test
    void linkWithUnsafeHrefStaysLiteral() {
        // 属性值装不下 ] ⇒ 退字面量（防 markup 串被撕坏）
        assertThat(render("[x](a]b)")).isEqualTo("[[x]](a]]b)");
    }

    @Test
    void markupParserProducesHyperlinkStyle() {
        // 裁决④ 实证①：markup 标签 ⇒ Span style 携 hyperlink
        var parsed = MarkupParser.parse("[link=https://e.com]pi[/]");
        var span = parsed.lines().get(0).spans().get(0);
        assertThat(span.style().hyperlink()).isPresent();
        assertThat(span.style().hyperlink().orElseThrow().url()).isEqualTo("https://e.com");
    }

    @Test
    void backendCellWriterEmitsOsc8Sequences() {
        // 裁决④ 实证②：AnsiCellWriter（AbstractBackend 渲染路）吐 OSC 8
        var sb = new StringBuilder();
        try (var writer = new AnsiCellWriter(sb::append)) {
            writer.writeCell(new Cell("p", Style.EMPTY.hyperlink("https://e.com")));
            writer.writeCell(new Cell("i", Style.EMPTY.hyperlink("https://e.com")));
            writer.writeCell(new Cell("x", Style.EMPTY));
        }
        assertThat(sb.toString())
            .contains("\u001b]8;;https://e.com\u001b\\")   // open
            .contains("\u001b]8;;\u001b\\");              // close
    }
}
