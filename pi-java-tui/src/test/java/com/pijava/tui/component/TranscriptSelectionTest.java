package com.pijava.tui.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.tamboui.style.Style;

/** L7 W6（S2b）：拖拽选区与文本提取。 */
class TranscriptSelectionTest {

    private static RenderRow row(String text) {
        return new RenderRow(text, Style.EMPTY);
    }

    @Test
    void extractsForwardSelectionAcrossRows() {
        var selection = new TranscriptSelection();
        selection.press(new TranscriptSelection.Cell(0, 2));
        selection.drag(new TranscriptSelection.Cell(1, 2));
        assertThat(selection.finish()).isTrue();
        assertThat(selection.text(List.of(row("abcdef"), row("ghijkl"))))
            .isEqualTo("cdef\ngh");
    }

    @Test
    void normalizesReverseSelection() {
        var selection = new TranscriptSelection();
        selection.press(new TranscriptSelection.Cell(1, 2));
        selection.drag(new TranscriptSelection.Cell(0, 2));
        selection.finish();
        assertThat(selection.text(List.of(row("abcdef"), row("ghijkl"))))
            .isEqualTo("cdef\ngh");
    }

    @Test
    void ignoresEmptyAndClickOnlySelections() {
        var selection = new TranscriptSelection();
        selection.press(new TranscriptSelection.Cell(0, 1));
        assertThat(selection.finish()).isFalse();
        assertThat(selection.text(List.of(row("abc")))).isEmpty();
    }

    @Test
    void stripsTrailingSpacesPerRow() {
        var selection = new TranscriptSelection();
        selection.press(new TranscriptSelection.Cell(0, 0));
        selection.drag(new TranscriptSelection.Cell(1, 3));
        selection.finish();
        assertThat(selection.text(List.of(row("ab   "), row("cd  "))))
            .isEqualTo("ab\ncd");
    }

    // ── S2：双击选词 / 三击行选 / 粒度拖动（pi :1236-1280） ─────────

    @Test
    void doubleClickSelectsWordAndTripleClickSelectsLine() {
        var rows = List.of(row("hello world"));
        var selection = new TranscriptSelection();
        selection.press(new TranscriptSelection.Cell(0, 2), 1000, rows);
        selection.finish();
        assertThat(selection.hasText()).isFalse();

        selection.press(new TranscriptSelection.Cell(0, 3), 1200, rows);
        selection.finish();
        assertThat(selection.text(rows)).isEqualTo("hello");

        selection.press(new TranscriptSelection.Cell(0, 4), 1400, rows);
        selection.finish();
        assertThat(selection.text(rows)).isEqualTo("hello world");

        selection.press(new TranscriptSelection.Cell(0, 6), 1500, rows);
        selection.finish();
        assertThat(selection.hasText()).isFalse();
    }

    @Test
    void clickCountResetsForOtherWordOrSlowSecondClick() {
        var rows = List.of(row("hello world"));
        var selection = new TranscriptSelection();
        selection.press(new TranscriptSelection.Cell(0, 3), 1000, rows);
        selection.finish();
        selection.press(new TranscriptSelection.Cell(0, 8), 1100, rows);
        selection.finish();
        assertThat(selection.hasText()).isFalse();

        selection.press(new TranscriptSelection.Cell(0, 3), 2000, rows);
        selection.finish();
        assertThat(selection.hasText()).isFalse();
    }

    @Test
    void wordGranularityDragExtendsAcrossWords() {
        var rows = List.of(row("hello world"));
        var selection = new TranscriptSelection();
        selection.press(new TranscriptSelection.Cell(0, 3), 1000, rows);
        selection.finish();
        selection.press(new TranscriptSelection.Cell(0, 4), 1100, rows);
        selection.drag(new TranscriptSelection.Cell(0, 8), rows);
        selection.finish();
        assertThat(selection.text(rows)).isEqualTo("hello world");
    }

    @Test
    void lineGranularityDragExtendsAcrossRows() {
        var rows = List.of(row("first"), row("second"));
        var selection = new TranscriptSelection();
        selection.press(new TranscriptSelection.Cell(0, 1), 1000, rows);
        selection.finish();
        selection.press(new TranscriptSelection.Cell(0, 2), 1100, rows);
        selection.finish();
        selection.press(new TranscriptSelection.Cell(0, 3), 1200, rows);
        selection.drag(new TranscriptSelection.Cell(1, 2), rows);
        selection.finish();
        assertThat(selection.text(rows)).isEqualTo("first\nsecond");
    }

    @Test
    void dragResetsClickCounting() {
        var rows = List.of(row("hello"));
        var selection = new TranscriptSelection();
        selection.press(new TranscriptSelection.Cell(0, 2), 1000, rows);
        selection.finish();
        selection.press(new TranscriptSelection.Cell(0, 3), 1100, rows);
        selection.drag(new TranscriptSelection.Cell(0, 4), rows);
        selection.finish();
        selection.press(new TranscriptSelection.Cell(0, 3), 1200, rows);
        selection.finish();
        assertThat(selection.hasText()).isFalse();
    }
}
