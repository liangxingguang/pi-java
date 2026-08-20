package com.pijava.tui.util;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import com.pijava.tui.component.SyntaxHighlighter;

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
 * the app shell sees them. This element renders {@code TextAreaState} itself
 * (text, placeholder, cursor block, per-line syntax highlighting via
 * {@link SyntaxHighlighter}) but is never focusable and never handles events,
 * so the app owns every key while the editor still looks and behaves like a
 * text input.</p>
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
        var buffer = frame.buffer();
        var baseStyle = context.currentStyle();
        if (state.text().isEmpty()) {
            var text = placeholder;
            if (text.length() > area.width()) {
                text = text.substring(0, area.width());
            }
            buffer.setString(area.left(), area.top(), text, Style.EMPTY.dim());
        } else {
            int scrollRow = state.scrollRow();
            int scrollCol = state.scrollCol();
            for (int r = 0; r < area.height(); r++) {
                int row = scrollRow + r;
                if (row >= state.lineCount()) {
                    break;
                }
                String line = state.getLine(row);
                String visible = scrollCol < line.length() ? line.substring(scrollCol) : "";
                if (!visible.isEmpty()) {
                    renderHighlighted(buffer, area, r, visible, baseStyle);
                }
            }
        }
        renderCursor(frame.buffer(), area);
    }

    /** 渲染光标块（对齐 TamboUI TextArea 的几何计算，永不闪烁）。 */
    private void renderCursor(Buffer buffer, Rect area) {
        int cursorRow = state.cursorRow();
        int cursorCol = state.cursorCol();
        int relativeRow = cursorRow - state.scrollRow();
        if (relativeRow < 0 || relativeRow >= area.height()) {
            return;
        }
        int relativeCol;
        if (cursorCol < state.scrollCol()) {
            relativeCol = -1;
        } else {
            String line = state.getLine(cursorRow);
            int from = Math.min(state.scrollCol(), line.length());
            int to = Math.min(cursorCol, line.length());
            relativeCol = CharWidth.of(line.substring(from, to));
        }
        if (relativeCol < 0 || relativeCol >= area.width()) {
            return;
        }
        int x = area.left() + relativeCol;
        int y = area.top() + relativeRow;
        buffer.set(x, y, buffer.get(x, y).patchStyle(
            Style.EMPTY.bg(EditorElement.CURSOR).fg(Color.BLACK)));
    }

    /** 按 SyntaxHighlighter 片段逐段写缓冲，超出区域宽度即停。 */
    private void renderHighlighted(Buffer buffer, Rect area, int row, String visible, Style base) {
        int x = area.left();
        int y = area.top() + row;
        for (var segment : SyntaxHighlighter.highlight(visible, base)) {
            if (x >= area.right()) {
                break;
            }
            var text = segment.text();
            if (x + text.length() > area.right()) {
                text = text.substring(0, area.right() - x);
            }
            buffer.setString(x, y, text, segment.style());
            x += text.length();
        }
    }
}
