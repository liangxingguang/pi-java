package com.pijava.tui.component;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * K31（pi editor.ts:359-360/704-722/2151-2174）：Ctrl+] / Ctrl+Alt+] 进入
 * 等待字符态，下一可打印字符执行多行、区分大小写、跳过当前位的跳转。
 */
class EditorComponentJumpTest {

    private static KeyEvent ctrlBracket() {
        return KeyEvent.ofChar(']', KeyModifiers.CTRL);
    }

    private static KeyEvent ctrlAltBracket() {
        return KeyEvent.ofChar(']', new KeyModifiers(true, true, false));
    }

    @Test
    void jumpForwardMovesToNextOccurrence() {
        var editor = new EditorComponent();
        editor.setText("abcabc");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.HOME));

        editor.onKeyEvent(ctrlBracket());
        editor.onKeyEvent(KeyEvent.ofChar('b'));

        editor.onKeyEvent(KeyEvent.ofChar('X'));
        assertThat(editor.getText()).isEqualTo("aXbcabc");
    }

    @Test
    void jumpBackwardMovesToPreviousOccurrence() {
        var editor = new EditorComponent();
        editor.setText("abcabc");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.END));

        editor.onKeyEvent(ctrlAltBracket());
        editor.onKeyEvent(KeyEvent.ofChar('b'));

        editor.onKeyEvent(KeyEvent.ofChar('X'));
        assertThat(editor.getText()).isEqualTo("abcaXbc");
    }

    @Test
    void pressingTheHotkeyAgainCancels() {
        var editor = new EditorComponent();
        editor.setText("abc");

        editor.onKeyEvent(ctrlBracket());
        editor.onKeyEvent(ctrlBracket());
        editor.onKeyEvent(KeyEvent.ofChar('X'));

        assertThat(editor.getText()).isEqualTo("abcX");
    }

    @Test
    void missingCharacterLeavesCursorInPlace() {
        var editor = new EditorComponent();
        editor.setText("abc");

        editor.onKeyEvent(ctrlBracket());
        editor.onKeyEvent(KeyEvent.ofChar('z'));
        editor.onKeyEvent(KeyEvent.ofChar('X'));

        assertThat(editor.getText()).isEqualTo("abcX");
    }

    @Test
    void jumpSearchesOtherLines() {
        var editor = new EditorComponent();
        editor.setText("abc\ndef");
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.HOME));
        editor.onKeyEvent(KeyEvent.ofKey(KeyCode.UP));

        editor.onKeyEvent(ctrlBracket());
        editor.onKeyEvent(KeyEvent.ofChar('f'));
        editor.onKeyEvent(KeyEvent.ofChar('X'));

        assertThat(editor.getText()).isEqualTo("abc\ndeXf");
    }
}
