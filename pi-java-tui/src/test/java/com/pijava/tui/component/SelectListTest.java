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
}
