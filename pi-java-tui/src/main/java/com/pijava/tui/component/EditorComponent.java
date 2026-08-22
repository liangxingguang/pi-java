package com.pijava.tui.component;

import java.util.ArrayDeque;
import java.util.function.Consumer;

import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.EditorElement;

import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;

/**
 * Multi-line input editor delegating to the TamboUI TextArea, with pi-aligned
 * editing (Phase 3 design §6; P6 alignment).
 *
 * <p>Ports the pi {@code tui/components/editor.ts} editing semantics onto the
 * TamboUI {@link TextAreaState}: fish-style undo (consecutive word chars
 * coalesce into one undo unit), an Emacs-style kill ring (Ctrl+K/U kill to
 * line end/start, Ctrl+W/Alt+D kill word backward/forward, Ctrl+Y yank,
 * Alt+Y yank-pop), word navigation (Ctrl+Left/Right, Alt+B/F) and the
 * Ctrl+A/E/B/F / Ctrl+Home/End cursor shortcuts. Undo restores both text and
 * cursor via {@code TextAreaState}'s cursor-move primitives (it has no cursor
 * setter).</p>
 */
public final class EditorComponent {

    /** Undo snapshot: editor text plus the grapheme-column cursor position. */
    private record Snapshot(String text, int row, int col) {
    }

    /** pi {@code lastAction}: consecutive kills accumulate, yank enables yank-pop. */
    private static final String KILL = "kill";
    private static final String YANK = "yank";
    private static final String TYPE_WORD = "type-word";

    private final TextAreaState state = new TextAreaState();
    private final EditorElement element;
    private final KillRing killRing = new KillRing();
    private final ArrayDeque<Snapshot> undoStack = new ArrayDeque<>();
    private Consumer<String> submitHandler = text -> { };
    private String lastAction;

    /** Creates the editor component backed by a fresh {@link TextAreaState}. */
    public EditorComponent() {
        this.element = TamboUIAdapter.editorElement(state)
            .placeholder("Type a message… (Enter send, Shift+Enter newline)")
            .fill()
            .addClass("EditorComponent");
    }

    /** The input row for the render tree: a {@code >} prompt plus the editor. */
    public Row render() {
        return TamboUIAdapter.row(
            TamboUIAdapter.text("> ").cyan(),
            element);
    }

    /**
     * Handle a key event by driving the {@link TextAreaState} directly.
     *
     * <p>The app shell owns all keys (the editor element has no id, so the
     * TamboUI focus system never routes keys to it); typing, navigation and
     * the editor's Ctrl/Alt bindings are applied here. Enter/Shift+Enter and
     * app.* shortcuts are handled by the app shell before this method is
     * reached, which also avoids the {@code TextAreaElement} swallowing Enter
     * as a newline.</p>
     */
    public void onKeyEvent(KeyEvent event) {
        var stroke = TamboUIAdapter.toStroke(event);
        // pi tui.editor.* bindings that do not clash with the app.* shortcuts.
        if (stroke.ctrl() && "w".equals(stroke.key())) {
            deleteWordBackward();
            return;
        }
        if (stroke.alt() && "backspace".equals(stroke.key())) {
            deleteWordBackward();
            return;
        }
        if (stroke.alt() && "d".equals(stroke.key())) {
            deleteWordForward();
            return;
        }
        if (stroke.alt() && "delete".equals(stroke.key())) {
            deleteWordForward();
            return;
        }
        if (stroke.ctrl() && "u".equals(stroke.key())) {
            deleteToLineStart();
            return;
        }
        if (stroke.ctrl() && "k".equals(stroke.key())) {
            deleteToLineEnd();
            return;
        }
        if (stroke.ctrl() && "y".equals(stroke.key())) {
            yank();
            return;
        }
        if (stroke.alt() && "y".equals(stroke.key())) {
            yankPop();
            return;
        }
        if (stroke.ctrl() && "-".equals(stroke.key())) {
            undo();
            return;
        }
        if (stroke.ctrl() && "left".equals(stroke.key()) || stroke.alt() && "b".equals(stroke.key())) {
            moveWordLeft();
            return;
        }
        if (stroke.ctrl() && "right".equals(stroke.key()) || stroke.alt() && "f".equals(stroke.key())) {
            moveWordRight();
            return;
        }
        // pi cursorLineStart/End and cursorLeft/Right extra defaults.
        if (stroke.ctrl() && "a".equals(stroke.key())
                || stroke.ctrl() && "home".equals(stroke.key())) {
            state.moveCursorToLineStart();
            return;
        }
        if (stroke.ctrl() && "e".equals(stroke.key())
                || stroke.ctrl() && "end".equals(stroke.key())) {
            state.moveCursorToLineEnd();
            return;
        }
        if (stroke.ctrl() && "b".equals(stroke.key())) {
            state.moveCursorLeft();
            return;
        }
        if (stroke.ctrl() && "f".equals(stroke.key())) {
            state.moveCursorRight();
            return;
        }
        if (event.hasCtrl() || event.hasAlt()) {
            return; // unbound ctrl/alt combinations never land in the text
        }
        switch (event.code()) {
            case KeyCode.CHAR -> typeChar(event.string());
            case KeyCode.BACKSPACE -> backspace();
            case KeyCode.DELETE -> forwardDelete();
            case KeyCode.LEFT -> state.moveCursorLeft();
            case KeyCode.RIGHT -> state.moveCursorRight();
            case KeyCode.UP -> state.moveCursorUp();
            case KeyCode.DOWN -> state.moveCursorDown();
            case KeyCode.HOME -> state.moveCursorToLineStart();
            case KeyCode.END -> state.moveCursorToLineEnd();
            case KeyCode.PAGE_UP -> state.scrollUp(1);
            case KeyCode.PAGE_DOWN -> state.scrollDown(1, state.lineCount());
            default -> { }
        }
    }

    // ── Editing operations (pi editor.ts semantics) ─────────────────────

    /** Insert a typed character with fish-style undo coalescing. */
    private void typeChar(String text) {
        if (text == null || text.isEmpty() || "\r".equals(text)) {
            // Empty input, or a stray carriage return: Enter is owned by the
            // app shell, so '\r' must never land in the text. Everything else
            // (spaces, tabs, '\n') is real input.
            return;
        }
        boolean whitespace = text.length() == 1 && Character.isWhitespace(text.charAt(0));
        if (whitespace || !TYPE_WORD.equals(lastAction)) {
            pushSnapshot();
        }
        lastAction = TYPE_WORD;
        state.insert(text);
    }

    private void backspace() {
        if (state.cursorCol() == 0 && state.cursorRow() == 0) {
            return;
        }
        pushSnapshot();
        lastAction = null;
        state.deleteBackward();
    }

    private void forwardDelete() {
        String line = state.getLine(state.cursorRow());
        if (state.cursorRow() == state.lineCount() - 1
                && state.cursorCol() >= EditorWordNav.charToGrapheme(line, line.length())) {
            return;
        }
        pushSnapshot();
        lastAction = null;
        state.deleteForward();
    }

    /** Delete from the start of the line to the cursor (Ctrl+U), killing the text. */
    private void deleteToLineStart() {
        String line = state.getLine(state.cursorRow());
        int col = state.cursorCol();
        if (col > 0) {
            pushSnapshot();
            int charCursor = EditorWordNav.graphemeToChar(line, col);
            killRing.push(line.substring(0, charCursor), true, KILL.equals(lastAction));
            lastAction = KILL;
            state.moveCursorToLineStart();
            deleteForwardTimes(col);
        } else if (state.cursorRow() > 0) {
            // At start of a line: merge with the previous line, killing the newline.
            pushSnapshot();
            killRing.push("\n", true, KILL.equals(lastAction));
            lastAction = KILL;
            state.moveCursorToLineStart();
            state.deleteBackward();
        }
    }

    /** Delete from the cursor to the end of the line (Ctrl+K), killing the text. */
    private void deleteToLineEnd() {
        String line = state.getLine(state.cursorRow());
        int col = state.cursorCol();
        int lineGraphemes = EditorWordNav.charToGrapheme(line, line.length());
        if (col < lineGraphemes) {
            pushSnapshot();
            int charCursor = EditorWordNav.graphemeToChar(line, col);
            killRing.push(line.substring(charCursor), false, KILL.equals(lastAction));
            lastAction = KILL;
            deleteForwardTimes(lineGraphemes - col);
        } else if (state.cursorRow() < state.lineCount() - 1) {
            // At end of a line: merge with the next line, killing the newline.
            pushSnapshot();
            killRing.push("\n", false, KILL.equals(lastAction));
            lastAction = KILL;
            state.deleteForward();
        }
    }

    /** Delete the word before the cursor (Ctrl+W / Alt+Backspace), killing it. */
    private void deleteWordBackward() {
        String line = state.getLine(state.cursorRow());
        int col = state.cursorCol();
        if (col == 0) {
            if (state.cursorRow() > 0) {
                pushSnapshot();
                killRing.push("\n", true, KILL.equals(lastAction));
                lastAction = KILL;
                state.moveCursorToLineStart();
                state.deleteBackward();
            }
            return;
        }
        pushSnapshot();
        boolean wasKill = KILL.equals(lastAction);
        int charCursor = EditorWordNav.graphemeToChar(line, col);
        int deleteFromChar = EditorWordNav.findWordBackward(line, charCursor);
        int deleteFromCol = EditorWordNav.charToGrapheme(line, deleteFromChar);
        killRing.push(line.substring(deleteFromChar, charCursor), true, wasKill);
        lastAction = KILL;
        // Place the cursor at the word start and delete forward only the word,
        // leaving the [0, deleteFrom) prefix in place.
        state.moveCursorToLineStart();
        moveCursorRightTimes(deleteFromCol);
        deleteForwardTimes(col - deleteFromCol);
    }

    /** Delete the word after the cursor (Alt+D / Alt+Delete), killing it. */
    private void deleteWordForward() {
        String line = state.getLine(state.cursorRow());
        int col = state.cursorCol();
        int lineGraphemes = EditorWordNav.charToGrapheme(line, line.length());
        if (col >= lineGraphemes) {
            if (state.cursorRow() < state.lineCount() - 1) {
                pushSnapshot();
                killRing.push("\n", false, KILL.equals(lastAction));
                lastAction = KILL;
                state.deleteForward();
            }
            return;
        }
        pushSnapshot();
        boolean wasKill = KILL.equals(lastAction);
        int charCursor = EditorWordNav.graphemeToChar(line, col);
        int deleteToChar = EditorWordNav.findWordForward(line, charCursor);
        int deleteToCol = EditorWordNav.charToGrapheme(line, deleteToChar);
        killRing.push(line.substring(charCursor, deleteToChar), false, wasKill);
        lastAction = KILL;
        deleteForwardTimes(deleteToCol - col);
    }

    /** Yank (paste) the most recent kill-ring entry at the cursor (Ctrl+Y). */
    private void yank() {
        if (killRing.length() == 0) {
            return;
        }
        pushSnapshot();
        state.insert(killRing.peek());
        lastAction = YANK;
    }

    /**
     * Cycle through the kill ring (Alt+Y): only works immediately after a yank
     * and replaces the last yanked text with the previous entry.
     */
    private void yankPop() {
        if (!YANK.equals(lastAction) || killRing.length() <= 1) {
            return;
        }
        pushSnapshot();
        deleteGraphemesBackward(EditorWordNav.graphemeCount(killRing.peek()));
        killRing.rotate();
        state.insert(killRing.peek());
        lastAction = YANK;
    }

    /** Undo the most recent edit unit, restoring text and cursor (Ctrl+-). */
    private void undo() {
        Snapshot snapshot = undoStack.pollLast();
        if (snapshot == null) {
            return;
        }
        state.setText(snapshot.text());
        state.moveCursorToStart();
        for (int i = 0; i < snapshot.row(); i++) {
            state.moveCursorDown();
        }
        for (int i = 0; i < snapshot.col(); i++) {
            state.moveCursorRight();
        }
        state.ensureCursorVisible(state.lineCount(), 80);
        lastAction = null;
    }

    /** Move the cursor one word backward (Ctrl+Left / Alt+B). */
    private void moveWordLeft() {
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
    private void moveWordRight() {
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

    private void pushSnapshot() {
        undoStack.addLast(new Snapshot(state.text(), state.cursorRow(), state.cursorCol()));
    }

    private void deleteForwardTimes(int times) {
        for (int i = 0; i < times; i++) {
            state.deleteForward();
        }
    }

    private void moveCursorRightTimes(int times) {
        for (int i = 0; i < times; i++) {
            state.moveCursorRight();
        }
    }

    private void deleteGraphemesBackward(int graphemes) {
        for (int i = 0; i < graphemes; i++) {
            state.deleteBackward();
        }
    }

    // ── Public API (unchanged surface, programmatic edits are undoable) ──

    /** Insert pasted text at the cursor (bracketed paste support). */
    public void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        pushSnapshot();
        lastAction = null;
        state.insert(text);
    }

    /** Replace the whole editor content (slash completion). */
    public void replaceText(String text) {
        String replacement = text == null ? "" : text;
        if (!state.text().equals(replacement)) {
            pushSnapshot();
        }
        lastAction = null;
        state.setText(replacement);
    }

    /** Register the submit callback (invoked by the app on plain Enter). */
    public void onSubmit(Consumer<String> handler) {
        this.submitHandler = handler;
    }

    /** Number of visible editor rows (1 for a single-line prompt). */
    public int lineCount() {
        return Math.max(1, state.lineCount());
    }

    /** Current editor text. */
    public String getText() {
        return state.text();
    }

    /** Clear the editor and its undo history. */
    public void clear() {
        state.clear();
        undoStack.clear();
        lastAction = null;
    }

    /** Replace the editor content (programmatic edits are undoable). */
    public void setText(String text) {
        String replacement = text == null ? "" : text;
        if (!state.text().equals(replacement)) {
            pushSnapshot();
        }
        lastAction = null;
        state.setText(replacement);
    }

    /** Insert a newline (Shift+Enter). */
    public void insertNewline() {
        pushSnapshot();
        lastAction = null;
        state.insert("\n");
    }

    /** Notify the submit handler with the current content and reset the editor. */
    public void submit() {
        var text = state.text();
        if (!text.isBlank()) {
            submitHandler.accept(text);
            state.clear();
            undoStack.clear();
            lastAction = null;
        }
    }
}
