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
 * without participating in the TamboUI focus system. The cursor is a steady
 * block (no blink/pulse): anything that animates is redrawn continuously,
 * which in regular mode fights the terminal's native scrollback — every
 * redraw yanks the viewport back to the bottom.
 *
 * <p>The stock {@code TextAreaElement} only draws its cursor while focused,
 * and TamboUI's focus routing would let it swallow Enter/arrow keys before
 * the app shell sees them. This element renders through the same
 * {@link TextArea} widget (text, placeholder, cursor block) but is never
 * focusable and never handles events, so the app owns every key while the
 * editor still looks and behaves like a text input.</p>
 */
public final class EditorElement extends StyledElement<EditorElement> {

    /** Static cursor block color (steady, Codex-CLI style). */
    public static final Color CURSOR = Color.hex("#00FFD7");

    private final TextAreaState state;
    private String placeholder = "";

    /**
     * Creates the editor element rendering the given state.
     *
     * @param state the text area state to render
     */
    public EditorElement(TextAreaState state) {
        this.state = state;
    }

    /** Sets the placeholder text shown while the editor is empty. */
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
            // A solid steady block: no shade stepping, so idle frames have
            // nothing to redraw and the terminal scrollback is left alone.
            .cursorStyle(Style.EMPTY.bg(EditorElement.CURSOR).fg(Color.BLACK))
            .placeholder(placeholder)
            .placeholderStyle(Style.EMPTY.dim())
            .build();
        widget.renderWithCursor(area, frame.buffer(), state, frame);
    }
}
