package com.pijava.tui.component;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 34（K27）：提示历史——{@code add} 存储语义（trim／空串／连续重复／100 上限）
 * 与 {@link EditorComponent} 的 ↑/↓ 导航（draft 还原、撤销、编辑键退出浏览）。
 * pi {@code editor.ts:347-350/428-434/456-472/928/941} 对位。
 */
class EditorHistoryTest {

    private static KeyEvent up() {
        return KeyEvent.ofKey(KeyCode.UP);
    }

    private static KeyEvent down() {
        return KeyEvent.ofKey(KeyCode.DOWN);
    }

    private static KeyEvent ctrl(char key) {
        return KeyEvent.ofChar(key, KeyModifiers.CTRL);
    }

    // ── add 存储语义 ─────────────────────────────────────────

    @Test
    void addTrimsAndSkipsBlankAndConsecutiveDuplicates() {
        var history = new EditorHistory();

        history.add("  first  ");
        assertThat(history.size()).isEqualTo(1);
        history.add("first");           // trim 后与首项重复 ⇒ 不叠
        assertThat(history.size()).isEqualTo(1);
        history.add("   ");             // 空白 ⇒ 不入
        history.add(null);
        assertThat(history.size()).isEqualTo(1);

        history.add("second");
        assertThat(history.size()).isEqualTo(2);
    }

    @Test
    void capsAtOneHundredEntriesDroppingOldest() {
        var history = new EditorHistory();
        for (int i = 1; i <= 101; i++) {
            history.add("m" + i);
        }
        assertThat(history.size()).isEqualTo(100);
    }

    // ── EditorComponent 导航 ────────────────────────────────

    private static EditorComponent editorWithHistory(String... submitted) {
        var editor = new EditorComponent();
        editor.onSubmit(text -> { });
        for (var text : submitted) {
            editor.setText(text);
            editor.submit();
        }
        return editor;
    }

    @Test
    void upRecallsMostRecentAndDownRestoresDraftWithCursor() {
        var editor = editorWithHistory("hello");
        editor.setText("draft");
        editor.onKeyEvent(ctrl('a'));   // draft 光标置行首（draft 快照即此位置）

        editor.onKeyEvent(up());
        assertThat(editor.getText()).isEqualTo("hello");

        editor.onKeyEvent(down());
        assertThat(editor.getText()).isEqualTo("draft");
        editor.onKeyEvent(KeyEvent.ofChar('!'));   // draft 光标回原行列（行首）
        assertThat(editor.getText()).isEqualTo("!draft");
    }

    @Test
    void upWalksToOldestThenStops() {
        var editor = editorWithHistory("one", "two");
        editor.setText("draft");
        editor.onKeyEvent(ctrl('a'));

        editor.onKeyEvent(up());
        assertThat(editor.getText()).isEqualTo("two");
        editor.onKeyEvent(up());
        assertThat(editor.getText()).isEqualTo("one");
        editor.onKeyEvent(up());        // 已最旧 ⇒ 停住
        assertThat(editor.getText()).isEqualTo("one");
    }

    @Test
    void downWalksBackToDraft() {
        var editor = editorWithHistory("one", "two");
        editor.setText("draft");
        editor.onKeyEvent(ctrl('a'));

        editor.onKeyEvent(up());
        editor.onKeyEvent(up());
        assertThat(editor.getText()).isEqualTo("one");
        editor.onKeyEvent(down());
        assertThat(editor.getText()).isEqualTo("two");
        editor.onKeyEvent(down());
        assertThat(editor.getText()).isEqualTo("draft");
    }

    @Test
    void upOnFirstLineMovesToLineStartBeforeHistory() {
        var editor = editorWithHistory("hello");
        editor.setText("ab");           // 单行、光标行尾

        editor.onKeyEvent(up());        // col>0 ⇒ 先行首，不取历史
        assertThat(editor.getText()).isEqualTo("ab");
        editor.onKeyEvent(KeyEvent.ofChar('X'));
        assertThat(editor.getText()).isEqualTo("Xab");

        editor.onKeyEvent(up());        // 光标在 X 后 ⇒ 仍先行首
        editor.onKeyEvent(up());        // 行首 ⇒ 取最近历史
        assertThat(editor.getText()).isEqualTo("hello");
    }

    @Test
    void firstEntryIsUndoable() {
        var editor = editorWithHistory("hello");
        editor.setText("draft");
        editor.onKeyEvent(ctrl('a'));

        editor.onKeyEvent(up());
        assertThat(editor.getText()).isEqualTo("hello");
        editor.onKeyEvent(ctrl('-'));
        assertThat(editor.getText()).isEqualTo("draft");
    }

    @Test
    void editingKeyResetsBrowsing() {
        var editor = editorWithHistory("hello");
        editor.setText("draft");
        editor.onKeyEvent(ctrl('a'));

        editor.onKeyEvent(up());
        assertThat(editor.getText()).isEqualTo("hello");
        editor.onKeyEvent(KeyEvent.ofChar('z'));    // 编辑键 ⇒ 退出浏览
        assertThat(editor.getText()).isEqualTo("zhello");

        // 若仍处浏览态，Down 会还原 draft；已退出 ⇒ Down 只到行尾。
        editor.onKeyEvent(down());
        assertThat(editor.getText()).isEqualTo("zhello");
    }

    @Test
    void blankSubmitIsNotRecorded() {
        var editor = new EditorComponent();
        editor.onSubmit(text -> { });
        editor.setText("   ");
        editor.submit();
        editor.clear();

        editor.onKeyEvent(up());
        assertThat(editor.getText()).isEmpty();
    }
}
