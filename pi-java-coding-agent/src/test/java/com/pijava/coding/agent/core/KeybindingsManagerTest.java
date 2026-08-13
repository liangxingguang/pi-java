package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: app.* keybinding resolution.
 */
class KeybindingsManagerTest {

    private final KeybindingsManager keys = new KeybindingsManager();

    @Test
    void resolvesCoreBindings() {
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of(
            "esc", false, false, false)))
            .isEqualTo(KeybindingsManager.INTERRUPT);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of(
            "c", true, false, false)))
            .isEqualTo(KeybindingsManager.CLEAR);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of(
            "d", true, false, false)))
            .isEqualTo(KeybindingsManager.EXIT);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of(
            "enter", false, true, false)))
            .isEqualTo(KeybindingsManager.FOLLOW_UP);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of(
            "tab", false, false, true)))
            .isEqualTo(KeybindingsManager.THINKING_CYCLE);
    }

    @Test
    void unboundStrokeReturnsNull() {
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of(
            "x", false, false, false))).isNull();
    }

    @Test
    void exposesAllActionIds() {
        assertThat(keys.actionIds()).hasSize(11);
        assertThat(keys.actionIds()).contains(KeybindingsManager.INTERRUPT);
    }
}
