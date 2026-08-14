package com.pijava.tui.util;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;

/**
 * Input row that renders {@link TextAreaState} with an always-visible cursor,
 * without participating in the TamboUI focus system.
 *
 * <p>The stock {@code TextAreaElement} only draws its cursor while focused,
 * and TamboUI's focus routing would let it swallow Enter/arrow keys before
 * the app shell sees them. This element renders through the same
 * {@link TextArea} widget (text, placeholder, cursor block) but is never
 * focusable and never handles events, so the app owns every key while the
 * editor still looks and behaves like a text input.</p>
 */
public final class EditorElement extends StyledElement<EditorElement> {

    /** Cursor shade cycle (bright → dim → bright), stepped every 100ms. */
    public static final Color[] BREATH = {
        Color.hex("#00FFD7"),
        Color.hex("#00D9B8"),
        Color.hex("#00B39A"),
        Color.hex("#008D7C"),
        Color.hex("#00665E"),
        Color.hex("#00413C"),
        Color.hex("#00665E"),
        Color.hex("#008D7C"),
        Color.hex("#00B39A"),
        Color.hex("#00D9B8"),
    };

    private static final int BREATH_STEP_MS = 100;

    private final TextAreaState state;
    private String placeholder = "";

    public EditorElement(TextAreaState state) {
        this.state = state;
    }

    public EditorElement placeholder(String placeholder) {
        this.placeholder = placeholder != null ? placeholder : "";
        return this;
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight,
                              RenderContext context) {
        int lines = Math.max(1, state.lineCount());
        int width = 20;
        for (int i = 0; i < state.lineCount(); i++) {
            width = Math.max(width, CharWidth.of(state.getLine(i)));
        }
        return Size.of(width, lines);
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        TextArea widget = TextArea.builder()
            .style(context.currentStyle())
            // A solid breathing block: the 50ms tick redraws this element, so
            // stepping the shade with wall-clock time gives a smooth
            // brighten/dim pulse like Codex's terminal cursor.
            .cursorStyle(Style.EMPTY.bg(breathShade()).fg(Color.BLACK))
            .placeholder(placeholder)
            .placeholderStyle(Style.EMPTY.dim())
            .build();
        widget.renderWithCursor(area, frame.buffer(), state, frame);
    }

    private static Color breathShade() {
        long now = System.nanoTime() / 1_000_000L;
        int index = (int) ((now / BREATH_STEP_MS) % BREATH.length);
        return BREATH[index];
    }
}
