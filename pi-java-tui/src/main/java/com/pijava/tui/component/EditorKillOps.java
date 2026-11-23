package com.pijava.tui.component;

import dev.tamboui.widgets.input.TextAreaState;

/**
 * Editor kill-ring / word-navigation operations (pi {@code editor.ts} semantics).
 *
 * <p>Split out of {@link EditorComponent} to keep the component under the
 * repository's 500-line limit (batch 29 E-series rule). Owns the kill ring and
 * the {@code lastAction} coalescing state (fish-style kill accumulation and
 * yank-pop gating); snapshots are pushed back through the supplied callback.</p>
 */
final class EditorKillOps {

    /** pi {@code lastAction}: consecutive kills accumulate, yank enables yank-pop. */
    static final String KILL = "kill";
    static final String YANK = "yank";
    static final String TYPE_WORD = "type-word";

    private final TextAreaState state;
    private final KillRing ring = new KillRing();
    private final Runnable pushSnapshot;
    private String lastAction;

    EditorKillOps(TextAreaState state, Runnable pushSnapshot) {
        this.state = state;
        this.pushSnapshot = pushSnapshot;
    }

    String lastAction() {
        return lastAction;
    }

    void lastAction(String value) {
        this.lastAction = value;
    }

    void markTypeWord() {
        lastAction = TYPE_WORD;
    }

    void clearAction() {
        lastAction = null;
    }

    private boolean isKill() {
        return KILL.equals(lastAction);
    }

    private boolean isYank() {
        return YANK.equals(lastAction);
    }

    // ── Editing operations (pi editor.ts semantics) ─────────────────────

    /** Delete from the start of the line to the cursor (Ctrl+U), killing the text. */
    void deleteToLineStart() {
        String line = state.getLine(state.cursorRow());
        int col = state.cursorCol();
        if (col > 0) {
            pushSnapshot.run();
            int charCursor = EditorWordNav.graphemeToChar(line, col);
            ring.push(line.substring(0, charCursor), true, isKill());
            lastAction = KILL;
            state.moveCursorToLineStart();
            deleteForwardTimes(col);
        } else if (state.cursorRow() > 0) {
            // At start of a line: merge with the previous line, killing the newline.
            pushSnapshot.run();
            ring.push("\n", true, isKill());
            lastAction = KILL;
            state.moveCursorToLineStart();
            state.deleteBackward();
        }
    }

    /** Delete from the cursor to the end of the line (Ctrl+K), killing the text. */
    void deleteToLineEnd() {
        String line = state.getLine(state.cursorRow());
        int col = state.cursorCol();
        int lineGraphemes = EditorWordNav.charToGrapheme(line, line.length());
        if (col < lineGraphemes) {
            pushSnapshot.run();
            int charCursor = EditorWordNav.graphemeToChar(line, col);
            ring.push(line.substring(charCursor), false, isKill());
            lastAction = KILL;
            deleteForwardTimes(lineGraphemes - col);
        } else if (state.cursorRow() < state.lineCount() - 1) {
            // At end of a line: merge with the next line, killing the newline.
            pushSnapshot.run();
            ring.push("\n", false, isKill());
            lastAction = KILL;
            state.deleteForward();
        }
    }

    /** Delete the word before the cursor (Ctrl+W / Alt+Backspace), killing it. */
    void deleteWordBackward() {
        String line = state.getLine(state.cursorRow());
        int col = state.cursorCol();
        if (col == 0) {
            if (state.cursorRow() > 0) {
                pushSnapshot.run();
                ring.push("\n", true, isKill());
                lastAction = KILL;
                state.moveCursorToLineStart();
                state.deleteBackward();
            }
            return;
        }
        pushSnapshot.run();
        boolean wasKill = isKill();
        int charCursor = EditorWordNav.graphemeToChar(line, col);
        int deleteFromChar = EditorWordNav.findWordBackward(line, charCursor);
        int deleteFromCol = EditorWordNav.charToGrapheme(line, deleteFromChar);
        ring.push(line.substring(deleteFromChar, charCursor), true, wasKill);
        lastAction = KILL;
        // Place the cursor at the word start and delete forward only the word,
        // leaving the [0, deleteFrom) prefix in place.
        state.moveCursorToLineStart();
        moveCursorRightTimes(deleteFromCol);
        deleteForwardTimes(col - deleteFromCol);
    }

    /** Delete the word after the cursor (Alt+D / Alt+Delete), killing it. */
    void deleteWordForward() {
        String line = state.getLine(state.cursorRow());
        int col = state.cursorCol();
        int lineGraphemes = EditorWordNav.charToGrapheme(line, line.length());
        if (col >= lineGraphemes) {
            if (state.cursorRow() < state.lineCount() - 1) {
                pushSnapshot.run();
                ring.push("\n", false, isKill());
                lastAction = KILL;
                state.deleteForward();
            }
            return;
        }
        pushSnapshot.run();
        boolean wasKill = isKill();
        int charCursor = EditorWordNav.graphemeToChar(line, col);
        int deleteToChar = EditorWordNav.findWordForward(line, charCursor);
        int deleteToCol = EditorWordNav.charToGrapheme(line, deleteToChar);
        ring.push(line.substring(charCursor, deleteToChar), false, wasKill);
        lastAction = KILL;
        deleteForwardTimes(deleteToCol - col);
    }

    /** Yank (paste) the most recent kill-ring entry at the cursor (Ctrl+Y). */
    void yank() {
        if (ring.length() == 0) {
            return;
        }
        pushSnapshot.run();
        state.insert(ring.peek());
        lastAction = YANK;
    }

    /**
     * Cycle through the kill ring (Alt+Y): only works immediately after a yank
     * and replaces the last yanked text with the previous entry.
     */
    void yankPop() {
        if (!isYank() || ring.length() <= 1) {
            return;
        }
        pushSnapshot.run();
        deleteGraphemesBackward(EditorWordNav.graphemeCount(ring.peek()));
        ring.rotate();
        state.insert(ring.peek());
        lastAction = YANK;
    }

    /** Move the cursor one word backward (Ctrl+Left / Alt+B). */
    void moveWordLeft() {
        lastAction = null;
        String line = state.getLine(state.cursorRow());
        if (state.cursorCol() == 0) {
            if (state.cursorRow() > 0) {
                state.moveCursorUp();
                state.moveCursorToLineEnd();
            }
            return;
        }
        int charCursor = EditorWordNav.graphemeToChar(line, state.cursorCol());
        int newCol = EditorWordNav.charToGrapheme(line,
            EditorWordNav.findWordBackward(line, charCursor));
        state.moveCursorToLineStart();
        moveCursorRightTimes(newCol);
    }

    /** Move the cursor one word forward (Ctrl+Right / Alt+F). */
    void moveWordRight() {
        lastAction = null;
        String line = state.getLine(state.cursorRow());
        int lineGraphemes = EditorWordNav.charToGrapheme(line, line.length());
        if (state.cursorCol() >= lineGraphemes) {
            if (state.cursorRow() < state.lineCount() - 1) {
                state.moveCursorDown();
                state.moveCursorToLineStart();
            }
            return;
        }
        int charCursor = EditorWordNav.graphemeToChar(line, state.cursorCol());
        int newCol = EditorWordNav.charToGrapheme(line,
            EditorWordNav.findWordForward(line, charCursor));
        state.moveCursorToLineStart();
        moveCursorRightTimes(newCol);
    }

    // ── TextAreaState driver helpers ────────────────────────────────────

    void deleteForwardTimes(int times) {
        for (int i = 0; i < times; i++) {
            state.deleteForward();
        }
    }

    void moveCursorRightTimes(int times) {
        for (int i = 0; i < times; i++) {
            state.moveCursorRight();
        }
    }

    void deleteGraphemesBackward(int graphemes) {
        for (int i = 0; i < graphemes; i++) {
            state.deleteBackward();
        }
    }
}
