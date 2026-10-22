package com.pijava.coding.agent.core;

import java.util.Map;

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
    void treeActionsNeverLeakIntoGlobalResolve() {
        // 批 9 评审 C1：tree 键与全局同键（ctrl+d/t/o/l）或新占（ctrl+u/a/x）——
        // 全局 resolve 只能见 11 个全局动作；漏进 tree 动作会让主屏按键被
        // handleAction 的 default 臂静默吞掉（ctrl+l 选模型/ctrl+u 删行头失效）。
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("l", true, false, false)))
            .isEqualTo(KeybindingsManager.MODEL_SELECT);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("d", true, false, false)))
            .isEqualTo(KeybindingsManager.EXIT);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("t", true, false, false)))
            .isEqualTo(KeybindingsManager.THINKING_TOGGLE);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("o", true, false, false)))
            .isEqualTo(KeybindingsManager.TOOLS_EXPAND);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("u", true, false, false)))
            .isNull();
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("a", true, false, false)))
            .isNull();
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("x", true, false, false)))
            .isNull();
        // tree 动作仍按 pi matches 形可查（overlay 语境）。
        assertThat(keys.matches(KeybindingsManager.TREE_FILTER_LABELED,
            KeybindingsManager.KeyStroke.of("l", true, false, false))).isTrue();
    }

    /** docs/21（§3.8，裁决③）：pi useWindowsKeybindings 四态（keybindings.ts:16-20）。 */
    @Test
    void windowsKeybindingsDetectionFollowsThePiRule() {
        assertThat(KeybindingsManager.useWindowsKeybindings("Windows 11", Map.of())).isTrue();
        assertThat(KeybindingsManager.useWindowsKeybindings("Linux",
            Map.of("WSL_DISTRO_NAME", "Ubuntu"))).isTrue();
        assertThat(KeybindingsManager.useWindowsKeybindings("Linux",
            Map.of("WSL_INTEROP", "/run/WSL"))).isTrue();
        assertThat(KeybindingsManager.useWindowsKeybindings("Linux", Map.of())).isFalse();
        assertThat(KeybindingsManager.useWindowsKeybindings("Mac OS X", Map.of())).isFalse();
    }

    /** docs/21（§3.8）：dequeue 键平台分叉——Windows/WSL ⇒ alt+q，其余 alt+up（单键不双绑）。 */
    @Test
    void dequeueBindingFollowsThePlatform() {
        var win = new KeybindingsManager(true);
        assertThat(win.matches(KeybindingsManager.DEQUEUE,
            KeybindingsManager.KeyStroke.of("q", false, true, false))).isTrue();
        assertThat(win.matches(KeybindingsManager.DEQUEUE,
            KeybindingsManager.KeyStroke.of("up", false, true, false))).isFalse();
        assertThat(win.strokeFor(KeybindingsManager.DEQUEUE))
            .isEqualTo(KeybindingsManager.KeyStroke.of("q", false, true, false));

        var nix = new KeybindingsManager(false);
        assertThat(nix.matches(KeybindingsManager.DEQUEUE,
            KeybindingsManager.KeyStroke.of("up", false, true, false))).isTrue();
        assertThat(nix.matches(KeybindingsManager.DEQUEUE,
            KeybindingsManager.KeyStroke.of("q", false, true, false))).isFalse();
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
