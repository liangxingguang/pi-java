package com.pijava.tui.component;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 fix: the editor drives its state directly from key events.
 */
class EditorComponentTest {

    @Test
    void charKeysInsertText() {
        var editor = new EditorComponent();
        editor.onKeyEvent(KeyEvent.ofChar('/'));
        editor.onKeyEvent(KeyEvent.ofChar('s'));

        assertThat(editor.getText()).isEqualTo("/s");
    }

    @Test
    void backspaceDeletesBackward() {
        var editor = new EditorComponent();
        editor.setText("abc");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE));

        assertThat(editor.getText()).isEqualTo("ab");
    }

    @Test
    void carriageReturnIsNotInserted() {
        var editor = new EditorComponent();
        editor.setText("cmd");
        editor.onKeyEvent(KeyEvent.ofChar('\r'));

        assertThat(editor.getText()).isEqualTo("cmd");
    }

    @Test
    void ctrlKeysAreSkipped() {
        var editor = new EditorComponent();
        editor.setText("x");
        editor.onKeyEvent(KeyEvent.ofChar('c', dev.tamboui.tui.event.KeyModifiers.CTRL));

        assertThat(editor.getText()).isEqualTo("x");
    }

    @Test
    void pastedTextIsInserted() {
        var editor = new EditorComponent();
        editor.insertText("pasted\ncontent");

        assertThat(editor.getText()).isEqualTo("pasted\ncontent");
    }

    @Test
    void spacesAndNewlinesAreInserted() {
        var editor = new EditorComponent();
        editor.onKeyEvent(KeyEvent.ofChar('h'));
        editor.onKeyEvent(KeyEvent.ofChar(' '));
        editor.onKeyEvent(KeyEvent.ofChar('w'));
        editor.onKeyEvent(KeyEvent.ofChar('\n'));

        assertThat(editor.getText()).isEqualTo("h w\n");
    }

    // ── 批 12 T4（docs/23 §3/§7）：editorPaddingX 双侧留白 ────────

    @Test
    void paddingXPadsBothSidesOfThePromptRow() throws Exception {
        var editor = new EditorComponent();
        editor.setPaddingXSource(() -> 2);
        editor.setText("x");

        var buffer = renderRow(editor, 20);
        // pad=2 ⇒ 行首 "  > "，尾部两格留白。
        assertThat(line(buffer, 0, 20)).startsWith("  > x");
        assertThat(buffer.get(19, 0).symbol()).isEqualTo(" ");
        assertThat(buffer.get(18, 0).symbol()).isEqualTo(" ");
    }

    @Test
    void paddingXDefaultsToZero() throws Exception {
        var editor = new EditorComponent();
        editor.setText("x");

        var buffer = renderRow(editor, 20);
        assertThat(line(buffer, 0, 20)).startsWith("> x");
    }

    @Test
    void paddingXClampsAtThree() throws Exception {
        var editor = new EditorComponent();
        editor.setPaddingXSource(() -> 99);
        editor.setText("x");

        var buffer = renderRow(editor, 20);
        assertThat(line(buffer, 0, 20)).startsWith("   > x");
    }

    private static dev.tamboui.buffer.Buffer renderRow(EditorComponent editor, int width)
            throws Exception {
        var buffer = dev.tamboui.buffer.Buffer.empty(new dev.tamboui.layout.Rect(0, 0, width, 1));
        var frame = dev.tamboui.terminal.Frame.forTesting(buffer);
        var focus = new dev.tamboui.toolkit.focus.FocusManager();
        var mark = dev.tamboui.tui.RenderThread.class.getDeclaredMethod("markAsRenderThread");
        mark.setAccessible(true);
        mark.invoke(null);
        try {
            editor.render().render(frame, new dev.tamboui.layout.Rect(0, 0, width, 1),
                new dev.tamboui.toolkit.element.DefaultRenderContext(focus,
                    new dev.tamboui.toolkit.event.EventRouter(focus,
                        new dev.tamboui.toolkit.element.ElementRegistry())));
        } finally {
            var clear = dev.tamboui.tui.RenderThread.class.getDeclaredMethod("clearRenderThread");
            clear.setAccessible(true);
            clear.invoke(null);
        }
        return buffer;
    }

    private static String line(dev.tamboui.buffer.Buffer buffer, int y, int width) {
        var out = new StringBuilder();
        for (int x = 0; x < width; x++) {
            var cell = buffer.get(x, y);
            if (!cell.isContinuation()) {
                out.append(cell.symbol());
            }
        }
        return out.toString();
    }
}
