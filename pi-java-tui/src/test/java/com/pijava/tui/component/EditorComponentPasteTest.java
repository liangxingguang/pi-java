package com.pijava.tui.component;

import java.util.Collections;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * K26（pi editor.ts:1276-1345/1411-1433）：大粘贴折叠为 {@code [paste #N ...]}
 * 标记、提交展开、撤销/退格携带注册表、归一化与路径前补空格。
 */
class EditorComponentPasteTest {

    private static String lines(int count) {
        return String.join("\n", Collections.nCopies(count, "x"));
    }

    @Test
    void smallPasteIsInsertedDirectly() {
        var editor = new EditorComponent();
        editor.insertText("one\ntwo");

        assertThat(editor.getText()).isEqualTo("one\ntwo");
    }

    @Test
    void elevenLinesFoldIntoLineMarker() {
        var editor = new EditorComponent();
        editor.insertText(lines(11));

        assertThat(editor.getText()).isEqualTo("[paste #1 +11 lines]");
    }

    @Test
    void thousandCharsFoldIntoCharMarker() {
        var editor = new EditorComponent();
        editor.insertText("a".repeat(1001));

        assertThat(editor.getText()).isEqualTo("[paste #1 1001 chars]");
        assertThat(editor.getExpandedText()).isEqualTo("a".repeat(1001));
    }

    @Test
    void submitExpandsMarkers() {
        var editor = new EditorComponent();
        var submitted = new StringBuilder();
        editor.onSubmit(submitted::append);
        editor.insertText("a".repeat(1001));

        editor.submit();

        assertThat(submitted.toString()).isEqualTo("a".repeat(1001));
        assertThat(editor.getText()).isEmpty();
    }

    @Test
    void undoRestoresPasteRegistry() {
        var editor = new EditorComponent();
        var submitted = new StringBuilder();
        editor.onSubmit(submitted::append);
        editor.insertText("a".repeat(1001));
        editor.onKeyEvent(KeyEvent.ofChar('x'));       // 新快照携带标记注册表
        assertThat(editor.getText()).isEqualTo("[paste #1 1001 chars]x");

        editor.onKeyEvent(KeyEvent.ofChar('-', KeyModifiers.CTRL));
        assertThat(editor.getText()).isEqualTo("[paste #1 1001 chars]");

        editor.submit();
        assertThat(submitted.toString()).isEqualTo("a".repeat(1001));
    }

    @Test
    void backspaceDeletesWholeMarker() {
        var editor = new EditorComponent();
        editor.insertText("a".repeat(1001));

        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE));

        assertThat(editor.getText()).isEmpty();
    }

    @Test
    void deletingLatestMarkerRenumbersRemaining() {
        var editor = new EditorComponent();
        var submitted = new StringBuilder();
        editor.onSubmit(submitted::append);
        editor.insertText("a".repeat(1001));
        editor.insertText("b".repeat(1001));
        assertThat(editor.getText())
            .isEqualTo("[paste #1 1001 chars][paste #2 1001 chars]");

        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE));
        assertThat(editor.getText()).isEqualTo("[paste #1 1001 chars]");

        editor.submit();
        assertThat(submitted.toString()).isEqualTo("a".repeat(1001));
    }

    @Test
    void setTextClearsRegistry() {
        var editor = new EditorComponent();
        editor.insertText("a".repeat(1001));
        editor.setText("[paste #1 1001 chars]");

        assertThat(editor.getExpandedText()).isEqualTo("[paste #1 1001 chars]");
    }

    @Test
    void pasteNormalizesLineEndingsAndTabs() {
        var editor = new EditorComponent();
        editor.insertText("a\r\nb\tc");

        assertThat(editor.getText()).isEqualTo("a\nb    c");
    }

    @Test
    void pastedPathAfterWordGetsLeadingSpace() {
        var editor = new EditorComponent();
        editor.onKeyEvent(KeyEvent.ofChar('x'));
        editor.insertText("/tmp/file");

        assertThat(editor.getText()).isEqualTo("x /tmp/file");
    }
}
