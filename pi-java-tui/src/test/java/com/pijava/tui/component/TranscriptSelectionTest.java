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
}
