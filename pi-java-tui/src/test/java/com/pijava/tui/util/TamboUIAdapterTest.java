package com.pijava.tui.util;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 fix: Enter/control-code normalization.
 */
class TamboUIAdapterTest {

    @Test
    void enterSendsAndLfOrShiftEnterInsertsNewline() {
        // Plain Enter / CR submits (Codex composer default submit=[Enter]).
        assertThat(TamboUIAdapter.isSendEnter(KeyEvent.ofKey(KeyCode.ENTER))).isTrue();
        assertThat(TamboUIAdapter.isSendEnter(KeyEvent.ofChar('\r'))).isTrue();
        // Shift/Alt/Ctrl variants never submit.
        assertThat(TamboUIAdapter.isSendEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.SHIFT))).isFalse();
        assertThat(TamboUIAdapter.isSendEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.ALT))).isFalse();
        assertThat(TamboUIAdapter.isSendEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.CTRL))).isFalse();
        assertThat(TamboUIAdapter.isSendEnter(KeyEvent.ofChar('\r', KeyModifiers.ALT))).isFalse();

        // LF (Shift+Enter/Ctrl+J fallback) and Shift/Alt+Enter insert newlines.
        assertThat(TamboUIAdapter.isNewlineEnter(KeyEvent.ofChar('\n'))).isTrue();
        assertThat(TamboUIAdapter.isNewlineEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.SHIFT))).isTrue();
        assertThat(TamboUIAdapter.isNewlineEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.ALT))).isTrue();
        assertThat(TamboUIAdapter.isNewlineEnter(KeyEvent.ofKey(KeyCode.ENTER))).isFalse();
        assertThat(TamboUIAdapter.isNewlineEnter(KeyEvent.ofChar('x'))).isFalse();
    }

    @Test
    void ctrlControlCodesMapToLetters() {
        assertThat(TamboUIAdapter.toStroke(
            KeyEvent.ofChar(3, KeyModifiers.CTRL)).key()).isEqualTo("c");
        assertThat(TamboUIAdapter.toStroke(
            KeyEvent.ofChar(4, KeyModifiers.CTRL)).key()).isEqualTo("d");
        assertThat(TamboUIAdapter.toStroke(
            KeyEvent.ofChar(16, KeyModifiers.CTRL)).key()).isEqualTo("p");
    }

    @Test
    void plainCharMapsToItself() {
        assertThat(TamboUIAdapter.toStroke(KeyEvent.ofChar('/')).key())
            .isEqualTo("/");
    }
}
