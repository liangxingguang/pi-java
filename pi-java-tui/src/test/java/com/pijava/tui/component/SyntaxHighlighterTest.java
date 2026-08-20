package com.pijava.tui.component;

import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-23: SyntaxHighlighter — 关键字/字符串/数字/注释分段。
 */
class SyntaxHighlighterTest {

    @Test
    void highlightsKeywordsStringsNumbersAndComments() {
        var segments = SyntaxHighlighter.highlight(
            "if (x > 3) return \"hi\" // note", Style.EMPTY);

        assertThat(segments).anyMatch(s -> s.text().equals("if")
            && !s.style().equals(Style.EMPTY));
        assertThat(segments).anyMatch(s -> s.text().equals("3")
            && !s.style().equals(Style.EMPTY));
        assertThat(segments).anyMatch(s -> s.text().equals("\"hi\"")
            && !s.style().equals(Style.EMPTY));
        assertThat(segments).anyMatch(s -> s.text().equals("// note")
            && !s.style().equals(Style.EMPTY));
    }

    @Test
    void plainTextStaysPlain() {
        var segments = SyntaxHighlighter.highlight("hello world", Style.EMPTY);
        assertThat(segments).hasSize(1);
        assertThat(segments.getFirst().text()).isEqualTo("hello world");
        assertThat(segments.getFirst().style()).isEqualTo(Style.EMPTY);
    }

    @Test
    void commentCoversRestOfLine() {
        var segments = SyntaxHighlighter.highlight("x = 1 # trailing", Style.EMPTY);
        assertThat(segments.getLast().text()).isEqualTo("# trailing");
    }

    @Test
    void keywordSegmentsAreContiguous() {
        var segments = SyntaxHighlighter.highlight("return value", Style.EMPTY);
        assertThat(segments.getFirst().text()).isEqualTo("return");
        assertThat(segments.getFirst().style()).isNotEqualTo(Style.EMPTY);
        assertThat(segments.getLast().text()).isEqualTo(" value");
    }
}
