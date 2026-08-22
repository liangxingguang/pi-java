package com.pijava.tui.component;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Word-boundary navigation and grapheme helpers (pi {@code tui/word-navigation.ts}).
 */
class EditorWordNavTest {

    @Test
    void findWordBackwardSkipsWhitespaceThenWord() {
        assertThat(EditorWordNav.findWordBackward("hello world", 11)).isEqualTo(6);
        assertThat(EditorWordNav.findWordBackward("hello   ", 8)).isEqualTo(0);
        assertThat(EditorWordNav.findWordBackward("hello", 0)).isEqualTo(0);
    }

    @Test
    void findWordBackwardSkipsWholePunctuationRun() {
        // Punctuation is its own unit (pi Intl.Segmenter): the cursor lands at
        // the word/punctuation boundary, i.e. right before the comma.
        assertThat(EditorWordNav.findWordBackward("hello,", 6)).isEqualTo(5);
        assertThat(EditorWordNav.findWordBackward("one.two", 7)).isEqualTo(4);
    }

    @Test
    void findWordBackwardPreservesPunctuationBoundaryInsideWord() {
        // "foo.bar|" → pi lands right after the last '.', i.e. at the start of "bar".
        assertThat(EditorWordNav.findWordBackward("foo.bar", 7)).isEqualTo(4);
    }

    @Test
    void findWordForwardSkipsWhitespaceThenWord() {
        assertThat(EditorWordNav.findWordForward("hello world", 0)).isEqualTo(5);
        assertThat(EditorWordNav.findWordForward("  hello", 0)).isEqualTo(7);
        assertThat(EditorWordNav.findWordForward("hello", 5)).isEqualTo(5);
    }

    @Test
    void findWordForwardStopsAtFirstPunctuation() {
        assertThat(EditorWordNav.findWordForward("one.two", 0)).isEqualTo(3);
    }

    @Test
    void graphemeCountsNewlinesAndCjk() {
        assertThat(EditorWordNav.graphemeCount("a\nb")).isEqualTo(3);
        assertThat(EditorWordNav.graphemeCount("你好")).isEqualTo(2);
        assertThat(EditorWordNav.graphemeCount("")).isEqualTo(0);
    }

    @Test
    void graphemeCharConversionsRoundTrip() {
        String line = "abc😀x"; // surrogate pair emoji + 'x'
        assertThat(EditorWordNav.graphemeCount(line)).isEqualTo(5);
        assertThat(EditorWordNav.graphemeToChar(line, 4)).isEqualTo(5); // after emoji
        assertThat(EditorWordNav.charToGrapheme(line, 5)).isEqualTo(4);
        assertThat(EditorWordNav.graphemeToChar(line, 5)).isEqualTo(6);
        assertThat(EditorWordNav.charToGrapheme(line, 6)).isEqualTo(5);
    }
}
