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

    /** docs/62（pi select-list.test.ts:117-130）：PgDn 5→10→11，再 PgUp 回 6。 */
    @Test
    void pageKeysMoveByPageSizeAndClamp() {
        var items = new java.util.ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            items.add("i" + i);
        }
        var longList = new SelectList<String>(items, s -> s);

        longList.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.PAGE_DOWN));
        assertThat(longList.selected()).contains("i5");
        longList.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.PAGE_DOWN));
        assertThat(longList.selected()).contains("i10");
        longList.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.PAGE_DOWN));
        assertThat(longList.selected()).contains("i11");
        longList.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.PAGE_DOWN));
        assertThat(longList.selected()).as("末尾钳位不 wrap").contains("i11");

        longList.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.PAGE_UP));
        assertThat(longList.selected()).contains("i6");
    }

    @Test
    void pageAtTheBoundaryReportsNoMovement() {
        assertThat(list.page(-1)).isFalse();
        var empty = new SelectList<String>(List.of(), s -> s);
        assertThat(empty.page(1)).isFalse();
    }

    @Test
    void windowKeepsTheSelectionVisible() {
        var items = new java.util.ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            items.add("i" + i);
        }
        var longList = new SelectList<String>(items, s -> s, 5);
        for (int i = 0; i < 11; i++) {
            longList.onKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.DOWN));
        }

        assertThat(longList.visibleRange()).hasSize(5).contains("i11");
        assertThat(longList.visibleRange().get(longList.windowSelectedIndex())).isEqualTo("i11");
    }

    @Test
    void ctrlKeysAreNotFilterInput() {
        assertThat(list.onKeyEvent(KeyEvent.ofChar(
            'a', dev.tamboui.tui.event.KeyModifiers.CTRL))).isFalse();
        assertThat(list.filter()).isEmpty();
    }
}
