package com.pijava.coding.agent.core;

import java.util.List;
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
        // 11 实键全局＋4 空绑定 session（批 14）＋12 tree/copy＋6 选择器 session（批 14）
        // ＋5 扩展对话框 tui.select.*/tui.input.newLine（批 17，overlay 表）。
        assertThat(keys.actionIds()).hasSize(38);
        assertThat(keys.actionIds()).contains(KeybindingsManager.INTERRUPT);
    }

    /** 批 17（T4）：扩展对话框五键在 overlay 表——enter/esc 等主屏键全局 resolve 零污染。 */
    @Test
    void extensionDialogKeysLiveInTheOverlayTable() {
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("enter", false, false, false)))
            .as("enter 不洩全局（主屏提交由应用壳直管）").isNull();
        assertThat(keys.matches(KeybindingsManager.SELECT_CONFIRM,
            KeybindingsManager.KeyStroke.of("enter", false, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.SELECT_CANCEL,
            KeybindingsManager.KeyStroke.of("esc", false, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.SELECT_CANCEL,
            KeybindingsManager.KeyStroke.of("c", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.INPUT_NEW_LINE,
            KeybindingsManager.KeyStroke.of("enter", false, false, true))).isTrue();
        assertThat(keys.matches(KeybindingsManager.INPUT_NEW_LINE,
            KeybindingsManager.KeyStroke.of("j", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.SELECT_UP,
            KeybindingsManager.KeyStroke.of("up", false, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.SELECT_DOWN,
            KeybindingsManager.KeyStroke.of("down", false, false, false))).isTrue();
    }

    /** 批 14（docs/25 T3）：选择器六键在 overlay 表，全局 resolve 零污染（批 9 C1 同构）。 */
    @Test
    void sessionSelectorKeysLiveInTheOverlayTable() {
        // 撞名核查：ctrl+p/ctrl+d 全局仍解 MODEL_CYCLE/EXIT
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("p", true, false, false)))
            .isEqualTo(KeybindingsManager.MODEL_CYCLE);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("d", true, false, false)))
            .isEqualTo(KeybindingsManager.EXIT);
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("s", true, false, false))).isNull();
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("n", true, false, false))).isNull();
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("r", true, false, false))).isNull();
        assertThat(keys.resolve(KeybindingsManager.KeyStroke.of("backspace", true, false, false))).isNull();
        // overlay 语境按动作 matches 可解
        assertThat(keys.matches(KeybindingsManager.SESSION_TOGGLE_PATH,
            KeybindingsManager.KeyStroke.of("p", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.SESSION_DELETE,
            KeybindingsManager.KeyStroke.of("d", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.SESSION_TOGGLE_SORT,
            KeybindingsManager.KeyStroke.of("s", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.SESSION_TOGGLE_NAMED_FILTER,
            KeybindingsManager.KeyStroke.of("n", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.SESSION_RENAME,
            KeybindingsManager.KeyStroke.of("r", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.SESSION_DELETE_NONINVASIVE,
            KeybindingsManager.KeyStroke.of("backspace", true, false, false))).isTrue();
    }

    /** 批 14（docs/25 T3）：app.session.new/tree/fork/resume 空绑定（pi defaultKeys: []）。 */
    @Test
    void sessionEmptyBindingsAreRegisteredButNeverResolve() {
        for (var action : List.of(KeybindingsManager.SESSION_NEW, KeybindingsManager.SESSION_TREE,
                KeybindingsManager.SESSION_FORK, KeybindingsManager.SESSION_RESUME)) {
            assertThat(keys.actionIds()).contains(action);
            assertThat(keys.strokeFor(action)).isNull();
            assertThat(keys.matches(action,
                KeybindingsManager.KeyStroke.of("n", true, false, false))).isFalse();
        }
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
