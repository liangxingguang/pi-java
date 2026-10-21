package com.pijava.tui.component.tree;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.SessionTreeBuilder;
import com.pijava.coding.agent.core.SessionTreeNode;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TreeSelectorOverlay} 的键分派与结局规格（docs/19 §6 19–22）：
 * pi handleInput（:1004-1110）的分派序、label 编辑臂、Enter/Esc 结局、
 * 恢复选中粒度。overlay 直驱（不跑 runner——批 8 手法）。
 */
class TreeSelectorOverlayTest {

    private static final Instant T0 = Instant.parse("2026-03-28T14:00:00Z");

    private static Entry.Message userMessage(String id, String parentId, String content) {
        return new Entry.Message(id, 0, parentId, T0,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(content)), null), null);
    }

    private static Entry.Message assistantMessage(String id, String parentId, String text) {
        return new Entry.Message(id, 0, parentId, T0,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)),
                StopReason.STOP, null),
            null);
    }

    private static List<Entry> branchingEntries() {
        return List.of(
            userMessage("user-1", null, "first message"),
            assistantMessage("asst-1", "user-1", "response 1"),
            userMessage("user-2", "asst-1", "second message"),
            assistantMessage("asst-2", "user-2", "response 2"),
            userMessage("user-3a", "asst-2", "branch A start"),
            assistantMessage("asst-3a", "user-3a", "branch A response"),
            userMessage("user-4a", "asst-3a", "branch A deep"),
            assistantMessage("asst-4a", "user-4a", "branch A leaf"),
            userMessage("user-3b", "asst-2", "branch B start"),
            assistantMessage("asst-3b", "user-3b", "branch B response"),
            userMessage("user-4b", "asst-3b", "branch B deep"));
    }

    private record Rig(TreeSelectorOverlay overlay, AtomicReference<String> selected,
                       AtomicBoolean cancelled, AtomicReference<String> copied,
                       AtomicReference<String> labelChanged, AtomicReference<String> labelValue) {}

    private static Rig rig(List<? extends Entry> entries, String currentLeafId,
                           String initialSelectedId, FilterMode initialFilterMode) {
        var selected = new AtomicReference<String>();
        var cancelled = new AtomicBoolean();
        var copied = new AtomicReference<String>();
        var labelChanged = new AtomicReference<String>();
        var labelValue = new AtomicReference<String>();
        var tree = SessionTreeBuilder.build(entries, id -> null, id -> null);
        var overlay = new TreeSelectorOverlay(tree, currentLeafId, 24,
            initialSelectedId, initialFilterMode,
            selected::set, () -> cancelled.set(true),
            (id, label) -> { labelChanged.set(id); labelValue.set(label); },
            copied::set, new KeybindingsManager(), () -> 80);
        return new Rig(overlay, selected, cancelled, copied, labelChanged, labelValue);
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(code, KeyModifiers.NONE, 0);
    }

    private static KeyEvent ctrl(char c) {
        return KeyEvent.ofChar(c, KeyModifiers.CTRL);
    }

    private static KeyEvent shiftCtrl(char c) {
        return KeyEvent.ofChar(c, new KeyModifiers(true, false, true));
    }

    private static KeyEvent shift(char c) {
        return KeyEvent.ofChar(Character.toUpperCase(c), KeyModifiers.SHIFT);
    }

    private static KeyEvent alt(KeyCode code) {
        return new KeyEvent(code, KeyModifiers.ALT, 0);
    }

    private static KeyEvent ctrlKey(KeyCode code) {
        return new KeyEvent(code, KeyModifiers.CTRL, 0);
    }

    // ── 19：键分派全表（pi 分派序；与全局同键无串扰）───────────

    @Test
    void keyDispatchCoversTheWholeTable() {
        var rig = rig(branchingEntries(), "asst-4a", null, null);
        var overlay = rig.overlay();
        var model = overlay.model();

        // 上下环绕
        overlay.onKeyEvent(key(KeyCode.UP));
        assertThat(model.selectedEntryId()).isEqualTo("user-4a");
        overlay.onKeyEvent(key(KeyCode.DOWN));
        assertThat(model.selectedEntryId()).isEqualTo("asst-4a");
        // 左/右＝翻页（maxVisibleLines=12 > 11 行 ⇒ pageDown 直落末行 user-4b）
        overlay.onKeyEvent(key(KeyCode.LEFT));
        assertThat(model.selectedEntryId()).isEqualTo("user-1");
        overlay.onKeyEvent(key(KeyCode.RIGHT));
        assertThat(model.selectedEntryId()).isEqualTo("user-4b");
        overlay.onKeyEvent(key(KeyCode.PAGE_UP));
        assertThat(model.selectedEntryId()).isEqualTo("user-1");
        overlay.onKeyEvent(key(KeyCode.PAGE_DOWN));
        assertThat(model.selectedEntryId()).isEqualTo("user-4b");

        // ctrl+left：段首上跳（B 分支 user-4b → user-3b）；alt+left 同臂折叠
        overlay.onKeyEvent(ctrlKey(KeyCode.LEFT));
        assertThat(model.selectedEntryId()).isEqualTo("user-3b");
        overlay.onKeyEvent(alt(KeyCode.LEFT));
        assertThat(model.isFolded("user-3b")).isTrue();
        // ctrl+right 展开；alt+right 段跳到叶
        overlay.onKeyEvent(ctrlKey(KeyCode.RIGHT));
        assertThat(model.isFolded("user-3b")).isFalse();
        overlay.onKeyEvent(alt(KeyCode.RIGHT));
        assertThat(model.selectedEntryId()).isEqualTo("user-4b");

        // 五个过滤键（与全局 ctrl+t/o/l/d 同键——overlay 上下文无串扰）
        overlay.onKeyEvent(ctrl('t'));
        assertThat(model.filterMode()).isEqualTo(FilterMode.NO_TOOLS);
        overlay.onKeyEvent(ctrl('t'));                    // 互切回 default
        assertThat(model.filterMode()).isEqualTo(FilterMode.DEFAULT);
        overlay.onKeyEvent(ctrl('u'));
        assertThat(model.filterMode()).isEqualTo(FilterMode.USER_ONLY);
        // pi 逐臂（:1062-1066）：labeledOnly 臂＝当前==labeled ? default : labeled
        overlay.onKeyEvent(ctrl('l'));
        assertThat(model.filterMode()).isEqualTo(FilterMode.LABELED_ONLY);
        overlay.onKeyEvent(ctrl('l'));
        assertThat(model.filterMode()).isEqualTo(FilterMode.DEFAULT);
        overlay.onKeyEvent(ctrl('a'));
        assertThat(model.filterMode()).isEqualTo(FilterMode.ALL);
        overlay.onKeyEvent(ctrl('d'));
        assertThat(model.filterMode()).isEqualTo(FilterMode.DEFAULT);

        // cycle 环
        overlay.onKeyEvent(ctrl('o'));
        assertThat(model.filterMode()).isEqualTo(FilterMode.NO_TOOLS);
        overlay.onKeyEvent(shiftCtrl('o'));
        assertThat(model.filterMode()).isEqualTo(FilterMode.DEFAULT);

        // 可打印字符 ⇒ 搜索；backspace 删尾字符；Esc 第一级清搜索
        overlay.onKeyEvent(KeyEvent.ofChar('b'));
        overlay.onKeyEvent(KeyEvent.ofChar('r'));
        assertThat(model.searchQuery()).isEqualTo("br");
        overlay.onKeyEvent(key(KeyCode.BACKSPACE));
        assertThat(model.searchQuery()).isEqualTo("b");
        overlay.onKeyEvent(key(KeyCode.ESCAPE));
        assertThat(model.searchQuery()).isEmpty();
        assertThat(overlay.isDone()).isFalse();           // 清搜索不取消

        // ctrl+x 复制（选中行全文经回调）
        overlay.onKeyEvent(ctrl('x'));
        assertThat(rig.copied().get()).isNotNull();

        // shift+t 时间戳开关
        boolean before = model.showLabelTimestamps();
        overlay.onKeyEvent(shift('t'));
        assertThat(model.showLabelTimestamps()).isEqualTo(!before);

        // Esc（无搜索）⇒ 取消
        overlay.onKeyEvent(key(KeyCode.ESCAPE));
        assertThat(overlay.isDone()).isTrue();
    }

    @Test
    void ctrlCCancelsLikeEscape() {
        var rig = rig(branchingEntries(), "asst-4a", null, null);
        rig.overlay().onKeyEvent(ctrl('c'));
        assertThat(rig.overlay().isDone()).isTrue();
        rig.overlay().apply(null, s -> { });
        assertThat(rig.cancelled()).isTrue();
        assertThat(rig.selected().get()).isNull();
    }

    // ── 20：label 编辑臂 ──────────────────────────────────────

    @Test
    void labelEditArmSubmitsAndCancels() {
        var rig = rig(branchingEntries(), "asst-4a", null, null);
        var overlay = rig.overlay();
        var model = overlay.model();

        overlay.onKeyEvent(shift('l'));                    // 换入编辑器
        assertThat(overlay.isEditingLabel()).isTrue();
        // 输入标签文本
        for (char c : "checkpoint".toCharArray()) {
            overlay.onKeyEvent(KeyEvent.ofChar(c));
        }
        overlay.onKeyEvent(key(KeyCode.ENTER));            // submit
        assertThat(overlay.isEditingLabel()).isFalse();
        assertThat(rig.labelChanged().get()).isEqualTo("asst-4a");
        assertThat(rig.labelValue().get()).isEqualTo("checkpoint");
        // 本地即时反映（pi :1402-1406）
        assertThat(model.filteredNodes().stream()
            .filter(n -> n.node().entry().id().equals("asst-4a"))
            .findFirst().orElseThrow().node().label()).isEqualTo("checkpoint");

        // 再开 ⇒ 初值＝当前 label；Esc 换回（无回调）
        overlay.onKeyEvent(shift('l'));
        assertThat(overlay.isEditingLabel()).isTrue();
        rig.labelChanged().set(null);
        overlay.onKeyEvent(key(KeyCode.ESCAPE));
        assertThat(overlay.isEditingLabel()).isFalse();
        assertThat(rig.labelChanged().get()).isNull();
        assertThat(overlay.isDone()).isFalse();            // 编辑臂 Esc 不关选择器

        // 空串 submit ⇒ label=null（清除）
        overlay.onKeyEvent(shift('l'));
        overlay.onKeyEvent(key(KeyCode.BACKSPACE));        // 删到空
        for (int i = 0; i < "checkpoint".length() - 1; i++) {
            overlay.onKeyEvent(key(KeyCode.BACKSPACE));
        }
        overlay.onKeyEvent(key(KeyCode.ENTER));
        assertThat(rig.labelValue().get()).isNull();
    }

    // ── 21：Enter/Esc 结局；apply 不动会话 ────────────────────

    @Test
    void enterConfirmsWithEntryIdAndApplyHandsItOver() {
        var rig = rig(branchingEntries(), "asst-4a", null, null);
        var overlay = rig.overlay();
        overlay.onKeyEvent(key(KeyCode.UP));               // 选中 user-4a
        overlay.onKeyEvent(key(KeyCode.ENTER));
        assertThat(overlay.isDone()).isTrue();
        // apply 把 entryId 交回调（navigate 合同）；session 参数不被触碰（传 null 实证）
        overlay.apply(null, s -> { });
        assertThat(rig.selected().get()).isEqualTo("user-4a");
        assertThat(rig.cancelled()).isFalse();
    }

    // ── 22：恢复选中＝entry 粒度（D40 销号实证）；非法过滤回退 ──

    @Test
    void initialSelectedIdRestoresAndInvalidFilterFallsBack() {
        var rig = rig(branchingEntries(), "asst-4a", "user-3b", null);
        assertThat(rig.overlay().model().selectedEntryId()).isEqualTo("user-3b");

        assertThat(FilterMode.parse("bogus")).isEqualTo(FilterMode.DEFAULT);
        assertThat(FilterMode.parse("no-tools")).isEqualTo(FilterMode.NO_TOOLS);
        assertThat(FilterMode.parse(null)).isEqualTo(FilterMode.DEFAULT);
    }

    @Test
    void renderProducesTitleHelpSearchAndTreeRows() {
        var rig = rig(branchingEntries(), "asst-4a", null, null);
        var element = rig.overlay().render();
        assertThat(element).isNotNull();                   // 渲染不炸（结构断言在集成层）
    }
}
