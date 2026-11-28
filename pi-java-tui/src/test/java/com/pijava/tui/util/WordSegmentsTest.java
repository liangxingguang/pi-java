package com.pijava.tui.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** S2：双击选词的分词/列映射（pi {@code tui-alt-screen.ts:1192-1227} 对位）。 */
class WordSegmentsTest {

    @Test
    void joinsWordRunsAcrossJoinerCharacters() {
        assertThat(WordSegments.wordRangeAt("foo/bar", 1))
            .isEqualTo(new WordSegments.Range(0, 7));
        assertThat(WordSegments.wordRangeAt("a-b.c", 3))
            .isEqualTo(new WordSegments.Range(0, 5));
        assertThat(WordSegments.wordRangeAt("src/main_test.v2", 8))
            .isEqualTo(new WordSegments.Range(0, 16));
    }

    @Test
    void spacesSeparateWords() {
        assertThat(WordSegments.wordRangeAt("foo bar", 0))
            .isEqualTo(new WordSegments.Range(0, 3));
        assertThat(WordSegments.wordRangeAt("foo bar", 5))
            .isEqualTo(new WordSegments.Range(4, 7));
    }

    @Test
    void clickingWhitespaceOrPunctuationSelectsThatSegment() {
        assertThat(WordSegments.wordRangeAt("foo bar", 3))
            .isEqualTo(new WordSegments.Range(3, 4));
        assertThat(WordSegments.wordRangeAt("hi, there", 2))
            .isEqualTo(new WordSegments.Range(2, 3));
    }

    @Test
    void blankAndOutOfRangeColumnsHaveNoWord() {
        assertThat(WordSegments.wordRangeAt("", 0)).isNull();
        assertThat(WordSegments.wordRangeAt("abc", -1)).isNull();
        assertThat(WordSegments.wordRangeAt("abc", 3)).isNull();
    }

    @Test
    void wideCjkCharactersMapToDisplayColumns() {
        // 中(0-1) 文(2-3)，点击第二格仍命中「中」。
        assertThat(WordSegments.wordRangeAt("中文 test", 1))
            .isEqualTo(new WordSegments.Range(0, 2));
        assertThat(WordSegments.wordRangeAt("中文 test", 2))
            .isEqualTo(new WordSegments.Range(2, 4));
        assertThat(WordSegments.wordRangeAt("中文 test", 5))
            .isEqualTo(new WordSegments.Range(5, 9));
    }
}
