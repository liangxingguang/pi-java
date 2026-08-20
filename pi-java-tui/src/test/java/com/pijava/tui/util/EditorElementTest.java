package com.pijava.tui.util;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.ElementRegistry;
import dev.tamboui.toolkit.event.EventRouter;
import dev.tamboui.toolkit.focus.FocusManager;
import dev.tamboui.tui.RenderThread;
import dev.tamboui.widgets.input.TextAreaState;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EditorElementTest {

    @Test
    void rendersTextAndAlwaysVisibleCursor() throws Exception {
        var state = new TextAreaState();
        state.setText("hello");
        var element = new EditorElement(state);
        var buffer = Buffer.empty(new Rect(0, 0, 30, 1));
        var frame = Frame.forTesting(buffer);
        var context = new DefaultRenderContext(
            new FocusManager(),
            new EventRouter(new FocusManager(), new ElementRegistry()));

        var mark = RenderThread.class.getDeclaredMethod("markAsRenderThread");
        mark.setAccessible(true);
        mark.invoke(null);
        try {
            element.render(frame, new Rect(0, 0, 30, 1), context);
        } finally {
            var clear = RenderThread.class.getDeclaredMethod("clearRenderThread");
            clear.setAccessible(true);
            clear.invoke(null);
        }

        // Text renders at the start of the area.
        assertThat(buffer.get(0, 0).symbol()).isEqualTo("h");
        // The cursor block sits right after the text as a steady cyan block.
        var cursorStyle = buffer.get(5, 0).style();
        assertThat(cursorStyle.bg()).contains(EditorElement.CURSOR);
        assertThat(cursorStyle.fg()).contains(dev.tamboui.style.Color.BLACK);
    }

    @Test
    void highlightsKeywordTokensInCurrentLine() throws Exception {
        var state = new TextAreaState();
        state.setText("return");
        var element = new EditorElement(state);
        var buffer = Buffer.empty(new Rect(0, 0, 30, 1));
        var frame = Frame.forTesting(buffer);
        var context = new DefaultRenderContext(
            new FocusManager(),
            new EventRouter(new FocusManager(), new ElementRegistry()));

        var mark = RenderThread.class.getDeclaredMethod("markAsRenderThread");
        mark.setAccessible(true);
        mark.invoke(null);
        try {
            element.render(frame, new Rect(0, 0, 30, 1), context);
        } finally {
            var clear = RenderThread.class.getDeclaredMethod("clearRenderThread");
            clear.setAccessible(true);
            clear.invoke(null);
        }

        // "return" is a highlighted keyword: the cell carries a foreground color.
        assertThat(buffer.get(0, 0).symbol()).isEqualTo("r");
        assertThat(buffer.get(0, 0).style().fg()).isPresent();
        // The cursor still sits after the text.
        assertThat(buffer.get(6, 0).style().bg()).contains(EditorElement.CURSOR);
    }
}
