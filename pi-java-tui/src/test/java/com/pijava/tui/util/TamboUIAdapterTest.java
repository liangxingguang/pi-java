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
    void carriageReturnCountsAsPlainEnter() {
        assertThat(TamboUIAdapter.isPlainEnter(KeyEvent.ofChar('\r'))).isTrue();
        assertThat(TamboUIAdapter.isPlainEnter(KeyEvent.ofChar('\n'))).isTrue();
        assertThat(TamboUIAdapter.isPlainEnter(KeyEvent.ofKey(KeyCode.ENTER))).isTrue();
        assertThat(TamboUIAdapter.isPlainEnter(KeyEvent.ofChar('x'))).isFalse();
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
