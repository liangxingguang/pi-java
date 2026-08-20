package com.pijava.tui.component;

import java.util.List;

import dev.tamboui.tui.event.KeyEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: SelectList navigation, confirm, cancel and filtering.
 */
class SelectListTest {

    private final SelectList<String> list =
        new SelectList<>(List.of("alpha", "beta", "gamma"), s -> s);

    @Test
    void upAndDownMoveSelection() {
        assertThat(list.selected()).contains("alpha");
        list.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.DOWN));
        assertThat(list.selected()).contains("beta");
        list.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.UP));
        assertThat(list.selected()).contains("alpha");
    }

    @Test
    void confirmKeepsSelection() {
        list.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.DOWN));
        assertThat(list.onKeyEvent(
            KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ENTER))).isTrue();
        assertThat(list.confirmed()).isTrue();
        assertThat(list.selected()).contains("beta");
    }

    @Test
    void cancelClearsSelection() {
        list.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE));
        assertThat(list.cancelled()).isTrue();
        assertThat(list.selected()).isEmpty();
    }

    @Test
    void filterRanksMatches() {
        list.filter("ga");
        assertThat(list.selected()).contains("gamma");
    }

    @Test
    void typingAccumulatesFilterAndRanks() {
        assertThat(list.onKeyEvent(KeyEvent.ofChar('g'))).isTrue();
        assertThat(list.filter()).isEqualTo("g");
        assertThat(list.onKeyEvent(KeyEvent.ofChar('a'))).isTrue();
        assertThat(list.selected()).contains("gamma");
    }

    @Test
    void backspaceRemovesLastFilterChar() {
        list.onKeyEvent(KeyEvent.ofChar('a'));
        list.onKeyEvent(KeyEvent.ofChar('l'));
        assertThat(list.filter()).isEqualTo("al");
        assertThat(list.onKeyEvent(KeyEvent.ofKey(
            dev.tamboui.tui.event.KeyCode.BACKSPACE))).isTrue();
        assertThat(list.filter()).isEqualTo("a");
        assertThat(list.selected()).contains("alpha");
    }

    @Test
    void firstEscClearsFilterSecondCancels() {
        list.onKeyEvent(KeyEvent.ofChar('z'));
        assertThat(list.onKeyEvent(KeyEvent.ofKey(
            dev.tamboui.tui.event.KeyCode.ESCAPE))).isTrue();
        assertThat(list.filter()).isEmpty();
        assertThat(list.cancelled()).isFalse();
        list.onKeyEvent(KeyEvent.ofKey(
            dev.tamboui.tui.event.KeyCode.ESCAPE));
        assertThat(list.cancelled()).isTrue();
    }

    @Test
    void ctrlKeysAreNotFilterInput() {
        assertThat(list.onKeyEvent(KeyEvent.ofChar(
            'a', dev.tamboui.tui.event.KeyModifiers.CTRL))).isFalse();
        assertThat(list.filter()).isEmpty();
    }
}
