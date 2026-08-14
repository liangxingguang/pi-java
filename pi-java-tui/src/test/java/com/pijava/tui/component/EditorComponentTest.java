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
}
