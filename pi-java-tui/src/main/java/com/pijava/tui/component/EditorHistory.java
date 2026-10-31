package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.widgets.input.TextAreaState;

/**
 * 批 34（K27）：提示历史（pi {@code editor.ts:347-350/428-434/456-472} 对位）。
 *
 * <p>entries 首项＝最近提交；{@code index == -1} 表示未浏览；首次从 -1 进入时
 * 记下当前文本与光标作 draft，回到底部时还原。导航的上/下键分派也在此收口
 * （{@link #handleNavigation}）——批 34 实施时 {@link EditorComponent} 已 487 行
 * （FileLength 500 闸门），故把「首/末行判定＋行首行尾跳转」一并移入本类。</p>
 */
final class EditorHistory {

    private static final int MAX = 100;

    /** 草稿快照（文本＋行列光标——{@link EditorComponent} 无光标 setter）。 */
    private record Draft(String text, int row, int col) {
    }

    private final List<String> entries = new ArrayList<>();   // index 0 = 最近提交
    private int index = -1;
    private Draft draft;

    /** pi {@code addToHistory}：trim、空串不入、连续重复不叠、100 上限。 */
    void add(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!entries.isEmpty() && entries.get(0).equals(trimmed)) {
            return;
        }
        entries.add(0, trimmed);
        if (entries.size() > MAX) {
            entries.remove(entries.size() - 1);
        }
    }

    int size() {
        return entries.size();
    }

    boolean browsing() {
        return index >= 0;
    }

    /** pi {@code exitHistoryBrowsing} 对位：丢弃浏览态与草稿（下次 ↑ 重新记 draft）。 */
    void reset() {
        index = -1;
        draft = null;
    }

    /**
     * 裸 ↑/↓ 的历史分派（pi {@code editor.ts:928/941} 对位）。
     *
     * <p>带修饰键或非 ↑/↓ ⇒ 退出浏览并返回 false（调用方继续常规键处理）。
     * 返回 true ⇒ 键已消费；false ⇒ 调用方按原臂处理（行内上/下移、行首/行尾）。</p>
     */
    boolean handleNavigation(KeyCode code, boolean modified, TextAreaState state,
                             Runnable snapshot) {
        if (modified || (code != KeyCode.UP && code != KeyCode.DOWN)) {
            reset();
            return false;
        }
        boolean firstLine = state.cursorRow() == 0;
        boolean lastLine = state.cursorRow() >= state.lineCount() - 1;
        if (code == KeyCode.UP) {
            if (firstLine && (state.text().isEmpty() || browsing() || state.cursorCol() == 0)) {
                return navigate(-1, state, snapshot);
            }
            if (firstLine) {
                state.moveCursorToLineStart();     // pi :932 顶行跳行首
                return true;
            }
            return false;
        }
        if (lastLine && browsing()) {
            return navigate(1, state, snapshot);
        }
        if (lastLine) {
            state.moveCursorToLineEnd();           // pi :943 底行跳行尾
            return true;
        }
        return false;
    }

    /** pi {@code navigateHistory}：Up(-1) 增号、Down(1) 减号；返回「是否有变化」。 */
    private boolean navigate(int direction, TextAreaState state, Runnable snapshot) {
        if (entries.isEmpty()) {
            return false;
        }
        int newIndex = index - direction;
        if (newIndex < -1 || newIndex >= entries.size()) {
            return false;
        }
        if (index == -1) {
            snapshot.run();      // 进入历史：先记撤销单元（pi pushUndoSnapshot）
            draft = new Draft(state.text(), state.cursorRow(), state.cursorCol());
        }
        index = newIndex;
        if (index == -1) {
            Draft saved = draft;
            draft = null;
            if (saved == null) {
                applyText(state, "", true);
            } else {
                applyDraft(state, saved);
            }
        } else {
            applyText(state, entries.get(index), direction == -1);   // up ⇒ "start"
        }
        return true;
    }

    /** pi {@code setTextInternal(text, placement)}：不触碰 undoStack（导航不入撤销）。 */
    private static void applyText(TextAreaState state, String text, boolean atStart) {
        state.setText(text);
        state.moveCursorToStart();
        if (!atStart) {
            for (int i = 0; i < state.lineCount() - 1; i++) {
                state.moveCursorDown();
            }
            state.moveCursorToLineEnd();
        }
    }

    /** draft 还原：文本＋原行列（TextAreaState 无光标 setter，逐格移动）。 */
    private static void applyDraft(TextAreaState state, Draft saved) {
        state.setText(saved.text());
        state.moveCursorToStart();
        for (int i = 0; i < saved.row(); i++) {
            state.moveCursorDown();
        }
        for (int i = 0; i < saved.col(); i++) {
            state.moveCursorRight();
        }
    }
}
