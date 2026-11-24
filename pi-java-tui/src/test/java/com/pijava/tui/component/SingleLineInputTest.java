package com.pijava.tui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.pijava.tui.util.TextLayout;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

/** L7 W6（C4）：单行输入状态。 */
class SingleLineInputTest {

    private static KeyEvent ctrl(char c) {
        return KeyEvent.ofChar(c, KeyModifiers.CTRL);
    }

    private static KeyEvent alt(char c) {
        return KeyEvent.ofChar(c, KeyModifiers.ALT);
    }

    @Test
    void typesAndBackspaces() {
        var input = new SingleLineInput("> ", "Find");
        input.handleKey(KeyEvent.ofChar('a'), null);
        input.handleKey(KeyEvent.ofChar('b'), null);
        input.handleKey(KeyEvent.ofChar('c'), null);
        assertEquals("abc", input.value());
        assertEquals(3, input.cursor());
        input.handleKey(KeyEvent.ofKey(KeyCode.BACKSPACE), null);
        assertEquals("ab", input.value());
        assertEquals(2, input.cursor());
    }

    @Test
    void cleansPastedText() {
        var input = new SingleLineInput("> ", "");
        input.insertText("a\r\nb\tc");
        assertEquals("ab    c", input.value());
    }

    @Test
    void movesByWordAndEdges() {
        var input = new SingleLineInput("> ", "");
        input.setValue("hello world");
        input.handleKey(ctrl('a'), null);
        assertEquals(0, input.cursor());
        input.handleKey(ctrl('f'), null);
        assertEquals(1, input.cursor());
        input.handleKey(KeyEvent.ofKey(KeyCode.END), null);
        assertEquals(11, input.cursor());
        input.handleKey(alt('b'), null);
        assertEquals(6, input.cursor());
        input.handleKey(alt('f'), null);
        assertEquals(11, input.cursor());
    }

    @Test
    void killsAndYanks() {
        var input = new SingleLineInput("> ", "");
        input.setValue("hello world");
        input.handleKey(ctrl('a'), null);
        input.handleKey(ctrl('k'), null);
        assertEquals("", input.value());
        input.handleKey(ctrl('y'), null);
        assertEquals("hello world", input.value());
    }

    @Test
    void undoesDeletion() {
        var input = new SingleLineInput("> ", "");
        input.insertText("abc");
        input.handleKey(KeyEvent.ofKey(KeyCode.BACKSPACE), null);
        assertEquals("ab", input.value());
        input.handleKey(ctrl('-'), null);
        assertEquals("abc", input.value());
    }

    @Test
    void scrollsHorizontallyWithHalfWidthRule() {
        var input = new SingleLineInput("> ", "");
        input.setValue("abcdefghijklmnopqrstuvwxyz");
        input.setCursorByColumn(26);
        var view = input.view(10);
        assertTrue(view.startColumn() > 0);
        assertTrue(TextLayout.displayWidth(view.text()) <= 10);
    }

    @Test
    void placesCursorByColumn() {
        var input = new SingleLineInput("> ", "");
        input.setValue("ab中文cd");
        input.setCursorByColumn(3);
        assertEquals(2, input.cursor());
        input.setCursorByColumn(5);
        assertEquals(3, input.cursor());
    }

    @Test
    void viewTracksCursorIndex() {
        var input = new SingleLineInput("> ", "");
        input.setValue("abcdefghij");
        input.setCursorByColumn(8);
        var view = input.view(6);
        assertTrue(view.cursorIndex() >= 0);
        assertTrue(view.cursorIndex() <= view.text().length());
    }
}
