package com.pijava.tui.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.tamboui.style.Style;

/** L7 W6（S2a）：转录搜索语料/匹配/缓存。 */
class TranscriptSearchIndexTest {

    private static RenderRow row(String text) {
        return new RenderRow(text, Style.EMPTY);
    }

    @Test
    void findsCaseInsensitiveMatchAndColumns() {
        var index = new TranscriptSearchIndex();
        var result = index.search(List.of(row("hello [bold]world[/]")), "WORLD");
        assertThat(result.changed()).isTrue();
        assertThat(result.matches()).hasSize(1);
        var segment = result.matches().get(0).segments().get(0);
        assertThat(segment.row()).isZero();
        assertThat(segment.startCol()).isEqualTo(6);
        assertThat(segment.endCol()).isEqualTo(11);
    }

    @Test
    void normalizesWhitespaceAndMatchesAcrossRows() {
        var index = new TranscriptSearchIndex();
        var result = index.search(List.of(row("hello"), row("world")), "hello   world");
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).segments()).hasSize(2);
    }

    @Test
    void reusesCacheWhenRowsAndQueryUnchanged() {
        var index = new TranscriptSearchIndex();
        var rows = List.of(row("alpha"), row("beta"));
        assertThat(index.search(rows, "beta").changed()).isTrue();
        assertThat(index.search(rows, "beta").changed()).isFalse();
        assertThat(index.search(rows, "gamma").matches()).isEmpty();
    }

    @Test
    void emptyQueryHasNoMatches() {
        var index = new TranscriptSearchIndex();
        var result = index.search(List.of(row("anything")), "   ");
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void matchKeyUsesFirstAndLastSegments() {
        var index = new TranscriptSearchIndex();
        var match = index.search(List.of(row("a"), row("b")), "a b").matches().get(0);
        assertThat(TranscriptSearchIndex.matchKey(match)).isEqualTo("0:0:1:1");
    }
}
