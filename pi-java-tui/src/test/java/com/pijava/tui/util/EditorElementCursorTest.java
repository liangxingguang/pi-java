package com.pijava.tui.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.tamboui.widgets.input.TextAreaState;

/** L7 W6（C20）：假光标与硬件光标互斥。 */
class EditorElementCursorTest {

    @Test
    void drawsReverseCursorWhenHardwareCursorOff() throws Exception {
        var state = new TextAreaState();
        state.setText("abc");
        var element = new EditorElement(state);
        var buffer = ElementRenderSupport.render(element, 10, 1);
        assertThat(buffer.get(3, 0).style().effectiveModifiers())
            .contains(dev.tamboui.style.Modifier.REVERSED);
    }

    @Test
    void suppressesFakeCursorWhenHardwareCursorOn() throws Exception {
        var state = new TextAreaState();
        state.setText("abc");
        var element = new EditorElement(state).showHardwareCursor(() -> true);
        var buffer = ElementRenderSupport.render(element, 10, 1);
        assertThat(buffer.get(3, 0).style().effectiveModifiers())
            .doesNotContain(dev.tamboui.style.Modifier.REVERSED);
    }
}
