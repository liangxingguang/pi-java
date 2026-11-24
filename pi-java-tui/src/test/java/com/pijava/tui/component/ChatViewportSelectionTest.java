package com.pijava.tui.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;

/** L7 W6（S2b/K22）：视口拖拽选区 → copy-on-select 回调。 */
class ChatViewportSelectionTest {

    private static ViewportInteraction interaction(List<String> sink) {
        var interaction = new ViewportInteraction();
        interaction.setSink(sink::add);
        interaction.setRows(List.of(
            new RenderRow("hello world", Style.EMPTY),
            new RenderRow("second line", Style.EMPTY)));
        return interaction;
    }

    @Test
    void dragPressReleaseCopiesSelectedText() {
        var sink = new ArrayList<String>();
        var interaction = interaction(sink);
        var area = new Rect(0, 0, 20, 5);
        interaction.press(0, 0, area, 0, 20);
        interaction.drag(5, 0, area, 0, 20);
        assertThat(interaction.finishAndCopy()).isTrue();
        assertThat(sink).containsExactly("hello");
    }

    @Test
    void emptySelectionDoesNotCopy() {
        var sink = new ArrayList<String>();
        var interaction = interaction(sink);
        var area = new Rect(0, 0, 20, 5);
        interaction.press(2, 0, area, 0, 20);
        assertThat(interaction.finishAndCopy()).isFalse();
        assertThat(sink).isEmpty();
    }

    @Test
    void copyActiveSelectionRepeatsWithoutClearing() {
        var sink = new ArrayList<String>();
        var interaction = interaction(sink);
        var area = new Rect(0, 0, 20, 5);
        interaction.press(0, 0, area, 0, 20);
        interaction.drag(5, 0, area, 0, 20);
        interaction.finishAndCopy();
        assertThat(interaction.hasActiveSelection()).isTrue();
        assertThat(interaction.copyActiveSelection()).isTrue();
        assertThat(sink).hasSize(2);
        interaction.clearSelection();
        assertThat(interaction.hasActiveSelection()).isFalse();
    }
}
