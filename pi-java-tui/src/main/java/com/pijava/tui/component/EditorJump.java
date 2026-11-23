package com.pijava.tui.component;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;

/**
 * Character jump mode (pi {@code editor.ts:359-360}, {@code :704-722},
 * {@code :2151-2174}): Ctrl+] waits for a character and jumps forward,
 * Ctrl+Alt+] jumps backward. Multi-line, case-sensitive, skips the cursor.
 */
final class EditorJump {

    private enum Mode { FORWARD, BACKWARD }

    private final TextAreaState state;
    private Mode mode;

    EditorJump(TextAreaState state) {
        this.state = state;
    }

    void cancel() {
        mode = null;
    }

    /**
     * Handle a key in jump mode or a jump trigger key.
     *
     * @return true when the event was consumed by jump mode
     */
    boolean handleKey(KeyEvent event, KeyStroke stroke) {
        if (mode != null) {
            if (isForward(stroke) || isBackward(stroke)) {
                mode = null;
                return true;
            }
            if (event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt()) {
                String typed = event.string();
                if (typed != null && !typed.isEmpty() && typed.codePointAt(0) >= 32) {
                    Mode direction = mode;
                    mode = null;
                    jumpToChar(typed.substring(0, Character.charCount(typed.codePointAt(0))),
                        direction == Mode.FORWARD);
                    return true;
                }
            }
            mode = null; // control character cancels and falls through
        }
        if (isForward(stroke)) {
            mode = Mode.FORWARD;
            return true;
        }
        if (isBackward(stroke)) {
            mode = Mode.BACKWARD;
            return true;
        }
        return false;
    }

    private static boolean isForward(KeyStroke stroke) {
        return stroke.ctrl() && !stroke.alt() && "]".equals(stroke.key());
    }

    private static boolean isBackward(KeyStroke stroke) {
        return stroke.ctrl() && stroke.alt() && "]".equals(stroke.key());
    }

    private void jumpToChar(String ch, boolean forward) {
        int row = state.cursorRow();
        int col = state.cursorCol();
        if (forward) {
            for (int r = row; r < state.lineCount(); r++) {
                String line = state.getLine(r);
                int from = r == row ? EditorWordNav.graphemeToChar(line, col) + 1 : 0;
                if (from > line.length()) {
                    continue;
                }
                int idx = line.indexOf(ch, from);
                if (idx >= 0) {
                    moveCursorTo(r, EditorWordNav.charToGrapheme(line, idx));
                    return;
                }
            }
        } else {
            for (int r = row; r >= 0; r--) {
                String line = state.getLine(r);
                int from = r == row ? EditorWordNav.graphemeToChar(line, col) - 1 : line.length() - 1;
                if (from < 0 || line.isEmpty()) {
                    continue;
                }
                int idx = line.lastIndexOf(ch, Math.min(from, line.length() - 1));
                if (idx >= 0) {
                    moveCursorTo(r, EditorWordNav.charToGrapheme(line, idx));
                    return;
                }
            }
        }
    }

    private void moveCursorTo(int row, int graphemeCol) {
        state.moveCursorToStart();
        for (int i = 0; i < row; i++) {
            state.moveCursorDown();
        }
        state.moveCursorToLineStart();
        for (int i = 0; i < graphemeCol; i++) {
            state.moveCursorRight();
        }
    }
}
