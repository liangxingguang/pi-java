package com.pijava.tui.util;

import java.util.List;

import com.pijava.tui.component.LogicalLine;
import com.pijava.tui.component.RenderRow;

import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 alignment design §7.1: logical-line splitting, render-time wrapping,
 * indentation, preformatted truncation, and display-width rules.
 */
class TextLayoutTest {

    @Test
    void escapeMarkupEscapesBracketsAndBackslashes() {
        assertThat(TextLayout.escapeMarkup("a[0] \\ [red]"))
            .isEqualTo("a[[0]] \\\\ [[red]]");
        assertThat(TextLayout.escapeMarkup(null)).isEmpty();
        assertThat(TextLayout.escapeMarkup("plain")).isEqualTo("plain");
    }

    @Test
    void splitOnNewlinesPreservesEmptyLines() {
        var lines = TextLayout.split("a\n\nb", false);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).markup()).isEqualTo("a");
        assertThat(lines.get(1).markup()).isEmpty();
        assertThat(lines.get(2).markup()).isEqualTo("b");
    }

    @Test
    void splitNormalizesCrlf() {
        assertThat(TextLayout.split("a\r\nb", false))
            .extracting(LogicalLine::markup)
            .containsExactly("a", "b");
    }

    @Test
    void splitPreformattedKeepsSingleLine() {
        var lines = TextLayout.split("a\nb", true);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).preformatted()).isTrue();
        assertThat(lines.get(0).markup()).isEqualTo("a\nb");
    }

    @Test
    void wrapBreaksAtWordBoundaries() {
        var rows = TextLayout.wrap(
            TextLayout.split("aaaa bbbb cccc dddd eeee", false), 10);
        assertThat(rows).extracting(RenderRow::text)
            .containsExactly("aaaa bbbb", "cccc dddd", "eeee");
    }

    @Test
    void wrapHardBreaksLongWords() {
        var rows = TextLayout.wrap(
            TextLayout.split("aaaaaaa b", false), 4);
        assertThat(rows).extracting(RenderRow::text)
            .containsExactly("aaaa", "aaa", "b");
    }

    @Test
    void wrapCountsWideCharactersAsTwoColumns() {
        var rows = TextLayout.wrap(TextLayout.split("你好世界", false), 4);
        assertThat(rows).extracting(RenderRow::text)
            .containsExactly("你好", "世界");
    }

    @Test
    void wrapAppliesFirstAndContinuationIndents() {
        var rows = TextLayout.wrap(
            List.of(new LogicalLine("aaaa bbbb cccc", 2, 4, false, Style.EMPTY)), 10);
        assertThat(rows).extracting(RenderRow::text)
            .containsExactly("  aaaa", "    bbbb", "    cccc");
    }

    @Test
    void wrapPreformattedTruncatesWithEllipsis() {
        var rows = TextLayout.wrap(
            List.of(new LogicalLine("abcdefghij", 0, 0, true, Style.EMPTY)), 5);
        assertThat(rows).extracting(RenderRow::text)
            .containsExactly("abcd…");
    }

    @Test
    void hardTruncateHonorsPreformattedIndent() {
        var rows = TextLayout.wrap(
            List.of(new LogicalLine("abcdefghij", 4, 4, true, Style.EMPTY)), 10);
        assertThat(rows).extracting(RenderRow::text)
            .containsExactly("    abcde…");
    }

    @Test
    void displayWidthFollowsWideCharacterRules() {
        assertThat(TextLayout.displayWidth("你好")).isEqualTo(4);
        assertThat(TextLayout.displayWidth("abc")).isEqualTo(3);
        assertThat(TextLayout.displayWidth("a你b")).isEqualTo(4);
        assertThat(TextLayout.displayWidth("")).isZero();
    }

    @Test
    void wrapWidthOneSplitsEveryCharacter() {
        var rows = TextLayout.wrap(TextLayout.split("abc", false), 1);
        assertThat(rows).extracting(RenderRow::text)
            .containsExactly("a", "b", "c");
    }

    @Test
    void emptyInputsProduceEmptyOrBlankRows() {
        assertThat(TextLayout.wrap(TextLayout.split("", false), 10)).isEmpty();
        assertThat(TextLayout.wrap(List.of(
            new LogicalLine(" ", 0, 0, false, Style.EMPTY)), 10))
            .hasSize(1);
    }
}
