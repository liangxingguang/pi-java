package com.pijava.tui.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 25（docs/36 T1）：markup 扫描器——转义三件套、开闭标签、容错、量宽。
 */
class MarkupTokensTest {

    @Test
    void emptyAndPlainInputs() {
        assertThat(MarkupTokens.tokenize(null)).isEmpty();
        assertThat(MarkupTokens.tokenize("")).isEmpty();
        assertThat(MarkupTokens.tokenize("plain"))
            .containsExactly(new MarkupTokens.Token.Text("plain"));
    }

    @Test
    void escapeTripletsStayLiteralText() {
        // [[ / ]] / \\ 归并为字面量文本段
        assertThat(MarkupTokens.tokenize("a[[b]]c\\\\d"))
            .containsExactly(new MarkupTokens.Token.Text("a[[b]]c\\\\d"));
        assertThat(MarkupTokens.visibleText("a[[b]]c\\\\d")).isEqualTo("a[b]c\\d");
    }

    @Test
    void backslashBeforeMetaEscapes() {
        // \[ \] \\ 三形；\x（x 非元字符）＝字面 \
        assertThat(MarkupTokens.visibleText("\\[x\\]y\\\\z")).isEqualTo("[x]y\\z");
        assertThat(MarkupTokens.visibleText("a\\nb")).isEqualTo("a\\nb");
    }

    @Test
    void openCloseAndAttributeTags() {
        assertThat(MarkupTokens.tokenize("[#FF0000]x[/]"))
            .containsExactly(
                new MarkupTokens.Token.Open("[#FF0000]", "#ff0000", null),
                new MarkupTokens.Token.Text("x"),
                new MarkupTokens.Token.Close("[/]", ""));
        assertThat(MarkupTokens.tokenize("[link=https://e.com]t[/link]"))
            .containsExactly(
                new MarkupTokens.Token.Open("[link=https://e.com]", "link", "https://e.com"),
                new MarkupTokens.Token.Text("t"),
                new MarkupTokens.Token.Close("[/link]", "link"));
    }

    @Test
    void nestedTagsKeepOrder() {
        var tokens = MarkupTokens.tokenize("[bold][#00FF00]x[/][/]");
        assertThat(tokens).hasSize(5);
        assertThat(tokens.get(0)).isEqualTo(new MarkupTokens.Token.Open("[bold]", "bold", null));
        assertThat(tokens.get(1)).isEqualTo(new MarkupTokens.Token.Open("[#00FF00]", "#00ff00", null));
        assertThat(tokens.get(3)).isEqualTo(new MarkupTokens.Token.Close("[/]", ""));
    }

    @Test
    void unclosedTagAtEndOfStringStillTag() {
        // parser 串尾 quirk：未闭合 [name 到串尾仍算标签
        assertThat(MarkupTokens.tokenize("a[red"))
            .containsExactly(
                new MarkupTokens.Token.Text("a"),
                new MarkupTokens.Token.Open("[red", "red", null));
    }

    @Test
    void bracketFollowedByBracketOrNewlineIsLiteral() {
        // 名字扫描遇 [ / 换行 ⇒ 整段不是标签，[ 退字面量
        assertThat(MarkupTokens.tokenize("a[[b]"))
            .containsExactly(new MarkupTokens.Token.Text("a[[b]"));
        assertThat(MarkupTokens.tokenize("a[br\nb]"))
            .containsExactly(new MarkupTokens.Token.Text("a[br\nb]"));
    }

    @Test
    void emptyNameNonClosingIsLiteral() {
        assertThat(MarkupTokens.tokenize("a[]b"))
            .containsExactly(new MarkupTokens.Token.Text("a[]b"));
    }

    @Test
    void displayWidthIgnoresTagsAndDecodesEscapes() {
        assertThat(MarkupTokens.displayWidth("[#FF0000]abc[/]")).isEqualTo(3);
        assertThat(MarkupTokens.displayWidth("[[x]]")).isEqualTo(3);
        assertThat(MarkupTokens.displayWidth("a\\\\b")).isEqualTo(3);
        assertThat(MarkupTokens.displayWidth("[bold]你好[/]")).isEqualTo(4);
        assertThat(MarkupTokens.displayWidth("plain")).isEqualTo(5);
    }
}
