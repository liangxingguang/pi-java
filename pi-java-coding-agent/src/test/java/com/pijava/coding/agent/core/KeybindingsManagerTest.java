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
        // 批 9（docs/19 §3.11）：11 全局＋12 tree/copy。
        assertThat(keys.actionIds()).hasSize(23);
        assertThat(keys.actionIds()).contains(KeybindingsManager.INTERRUPT);
    }

    @Test
    void treeActionsResolveTheirDefaultStrokes() {
        // foldOrUp 双键（ctrl／alt）任一命中（pi keybindings.ts:83-167）。
        assertThat(keys.matches(KeybindingsManager.TREE_FOLD_OR_UP,
            KeybindingsManager.KeyStroke.of("left", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.TREE_FOLD_OR_UP,
            KeybindingsManager.KeyStroke.of("left", false, true, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.TREE_FOLD_OR_UP,
            KeybindingsManager.KeyStroke.of("left", false, false, false))).isFalse();
        // matches 按动作判定——同名键（ctrl+d＝EXIT/TREE_FILTER_DEFAULT）各解各的。
        assertThat(keys.matches(KeybindingsManager.TREE_FILTER_DEFAULT,
            KeybindingsManager.KeyStroke.of("d", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.EXIT,
            KeybindingsManager.KeyStroke.of("d", true, false, false))).isTrue();
        assertThat(keys.strokeFor(KeybindingsManager.TREE_FOLD_OR_UP))
            .isEqualTo(KeybindingsManager.KeyStroke.of("left", true, false, false));
    }
}
