package com.pijava.tui.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.tui.component.RenderRow;

import dev.tamboui.style.Style;

/** L7 W6（S2a/S2b）：按列切分 span 并上高亮。 */
class LineHighlighterTest {

    @Test
    void splitsSpansAtHighlightBoundaries() {
        var row = new RenderRow("abc[bold]def[/]", Style.EMPTY);
        var line = LineHighlighter.apply(row, List.of(
            new LineHighlighter.Range(1, 4, Style.EMPTY.reversed())));
        assertThat(line.spans()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(String.join("", line.spans().stream().map(s -> s.content()).toList()))
            .isEqualTo("abcdef");
        var highlighted = line.spans().stream()
            .filter(s -> s.style().effectiveModifiers()
                .contains(dev.tamboui.style.Modifier.REVERSED))
            .map(s -> s.content()).toList();
        assertThat(String.join("", highlighted)).isEqualTo("bcd");
    }

    @Test
    void wideCharactersAreNotSplit() {
        var row = new RenderRow("a中b", Style.EMPTY);
        var line = LineHighlighter.apply(row, List.of(
            new LineHighlighter.Range(1, 2, Style.EMPTY.underlined())));
        assertThat(String.join("", line.spans().stream().map(s -> s.content()).toList()))
            .isEqualTo("a中b");
        assertThat(line.spans().stream()
            .anyMatch(s -> s.style().effectiveModifiers()
                .contains(dev.tamboui.style.Modifier.UNDERLINED))).isTrue();
    }
}
