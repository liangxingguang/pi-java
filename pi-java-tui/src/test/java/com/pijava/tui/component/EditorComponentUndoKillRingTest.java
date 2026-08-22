package com.pijava.tui.component;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6 alignment: pi {@code tui/components/editor.ts} editing semantics on the
 * TamboUI-backed editor — undo (fish-style coalescing), kill ring, word
 * navigation and the Ctrl+A/E/B/F cursor shortcuts.
 */
class EditorComponentUndoKillRingTest {

    @Test
    void undoRestoresTextWithWordCoalescing() {
        var editor = new EditorComponent();
        type(editor, "hello world");
        // Snapshots: "" (at 'h'), "hello" (the space captures the text before
        // itself, so undo removes the space and the following word together).
        editor.onKeyEvent(KeyEvent.ofChar('-', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("hello");
        editor.onKeyEvent(KeyEvent.ofChar('-', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("");
    }

    @Test
    void backspaceIsUndoable() {
        var editor = new EditorComponent();
        editor.setText("ab");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE));
        assertThat(editor.getText()).isEqualTo("a");
        editor.onKeyEvent(KeyEvent.ofChar('-', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("ab");
    }

    @Test
    void ctrlKDeletesToLineEndAndYankRestores() {
        var editor = new EditorComponent();
        editor.setText("hello world");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.HOME));
        editor.onKeyEvent(KeyEvent.ofChar('k', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("");
        editor.onKeyEvent(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("hello world");
    }

    @Test
    void ctrlUDeletesToLineStartAndYankRestores() {
        var editor = new EditorComponent();
        editor.setText("hello world");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.END));
        editor.onKeyEvent(KeyEvent.ofChar('u', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("");
        editor.onKeyEvent(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("hello world");
    }

    @Test
    void ctrlWDeletesWordBackwardKeepingPrefix() {
        var editor = new EditorComponent();
        editor.setText("hello world");
        editor.onKeyEvent(KeyEvent.ofChar('w', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("hello ");
    }

    @Test
    void altDDeletesWordForward() {
        var editor = new EditorComponent();
        editor.setText("hello world");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.HOME));
        editor.onKeyEvent(KeyEvent.ofChar('d', KeyModifiers.ALT));
        assertThat(editor.getText()).isEqualTo(" world");
    }

    @Test
    void consecutiveWordKillsAccumulateInKillRing() {
        var editor = new EditorComponent();
        editor.setText("a b");
        editor.onKeyEvent(KeyEvent.ofChar('w', KeyModifiers.CTRL)); // kill "b"
        editor.onKeyEvent(KeyEvent.ofChar('w', KeyModifiers.CTRL)); // kill "a " → accumulates "a b"
        assertThat(editor.getText()).isEqualTo("");
        editor.onKeyEvent(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("a b");
    }

    @Test
    void yankPopCyclesKillRing() {
        var editor = new EditorComponent();
        editor.setText("first");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.HOME));
        editor.onKeyEvent(KeyEvent.ofChar('k', KeyModifiers.CTRL)); // kill "first"
        type(editor, "second");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.HOME));
        editor.onKeyEvent(KeyEvent.ofChar('k', KeyModifiers.CTRL)); // kill "second"
        editor.onKeyEvent(KeyEvent.ofChar('y', KeyModifiers.CTRL)); // yank "second"
        assertThat(editor.getText()).isEqualTo("second");
        editor.onKeyEvent(KeyEvent.ofChar('y', KeyModifiers.ALT)); // yank-pop → "first"
        assertThat(editor.getText()).isEqualTo("first");
    }

    @Test
    void undoRestoresAfterYankStepByStep() {
        var editor = new EditorComponent();
        editor.setText("abc");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.HOME));
        editor.onKeyEvent(KeyEvent.ofChar('k', KeyModifiers.CTRL)); // kill "abc" → ""
        editor.onKeyEvent(KeyEvent.ofChar('y', KeyModifiers.CTRL)); // yank "abc"
        editor.onKeyEvent(KeyEvent.ofChar('-', KeyModifiers.CTRL)); // undo yank → ""
        assertThat(editor.getText()).isEqualTo("");
        editor.onKeyEvent(KeyEvent.ofChar('-', KeyModifiers.CTRL)); // undo kill → "abc"
        assertThat(editor.getText()).isEqualTo("abc");
    }

    @Test
    void ctrlLeftAndRightMoveCursorWordwise() {
        var editor = new EditorComponent();
        editor.setText("hello world");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.END));
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.LEFT, KeyModifiers.CTRL));
        editor.onKeyEvent(KeyEvent.ofChar('X'));
        assertThat(editor.getText()).isEqualTo("hello Xworld");
    }

    @Test
    void ctrlAAndCtrlEJumpToLineEdges() {
        var editor = new EditorComponent();
        editor.setText("hello");
        editor.onKeyEvent(KeyEvent.ofChar('a', KeyModifiers.CTRL));
        editor.onKeyEvent(KeyEvent.ofChar('X'));
        assertThat(editor.getText()).isEqualTo("Xhello");
        editor.onKeyEvent(KeyEvent.ofChar('e', KeyModifiers.CTRL));
        editor.onKeyEvent(KeyEvent.ofChar('Y'));
        assertThat(editor.getText()).isEqualTo("XhelloY");
    }

    @Test
    void multiLineKillAndUndoRestoreCursor() {
        var editor = new EditorComponent();
        editor.setText("ab\ncd");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.UP)); // cursor to line 1
        editor.onKeyEvent(KeyEvent.ofChar('k', KeyModifiers.CTRL)); // at line end → join next
        assertThat(editor.getText()).isEqualTo("abcd");
        editor.onKeyEvent(KeyEvent.ofChar('-', KeyModifiers.CTRL)); // undo
        assertThat(editor.getText()).isEqualTo("ab\ncd");
    }

    private static void type(EditorComponent editor, String text) {
        for (int i = 0; i < text.length(); i++) {
            editor.onKeyEvent(KeyEvent.ofChar(text.charAt(i)));
        }
    }
}
