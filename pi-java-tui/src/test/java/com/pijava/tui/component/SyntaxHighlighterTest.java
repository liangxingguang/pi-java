package com.pijava.tui.component;

import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.tui.theme.TuiThemeBridge;

import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-23: SyntaxHighlighter — pi 九类语法分段与保守 plain 行为。
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

    @Test
    void highlightsFunctionsTypesOperatorsAndPunctuation() {
        var segments = SyntaxHighlighter.highlight("int count = compute(x);", Style.EMPTY);
        assertThat(segments).anyMatch(s -> s.text().equals("int")
            && !s.style().equals(Style.EMPTY));
        assertThat(segments).anyMatch(s -> s.text().equals("compute")
            && !s.style().equals(Style.EMPTY));
        assertThat(segments).anyMatch(s -> s.text().equals("=")
            && !s.style().equals(Style.EMPTY));
        assertThat(segments).anyMatch(s -> s.text().equals("(")
            && !s.style().equals(Style.EMPTY));
        assertThat(segments).anyMatch(s -> s.text().equals(";")
            && !s.style().equals(Style.EMPTY));
        assertThat(segments).anyMatch(s -> s.text().contains("count")
            && s.style().equals(Style.EMPTY));
        assertThat(segments).anyMatch(s -> s.text().equals("x")
            && s.style().equals(Style.EMPTY));
    }

    @Test
    void tokenColorsFollowActiveTheme() {
        assertThat(SyntaxHighlighter.functionColor())
            .isEqualTo(TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_FUNCTION));
        assertThat(SyntaxHighlighter.variableColor())
            .isEqualTo(TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_VARIABLE));
        assertThat(SyntaxHighlighter.typeColor())
            .isEqualTo(TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_TYPE));
        assertThat(SyntaxHighlighter.operatorColor())
            .isEqualTo(TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_OPERATOR));
        assertThat(SyntaxHighlighter.punctuationColor())
            .isEqualTo(TuiThemeBridge.tokenColor(ThemeToken.SYNTAX_PUNCTUATION));
    }

    @Test
    void segmentsCoverWholeLine() {
        var line = "int count = compute(x); // note 's' 1.5";
        var segments = SyntaxHighlighter.highlight(line, Style.EMPTY);
        var joined = new StringBuilder();
        for (var segment : segments) {
            joined.append(segment.text());
        }
        assertThat(joined.toString()).isEqualTo(line);
    }
}
