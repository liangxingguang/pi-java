package com.pijava.tui.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * L7 W6（C4，pi {@code tui/components/input.ts} 对位）：单行输入状态。
 *
 * <p>值＋字符光标；括号粘贴清洗（去换行、{@code \t}→4 空格）；Emacs 风格
 * kill ring（Ctrl+K/U/W、Alt+D、Ctrl+Y/Alt+Y）；undo（连续词字符合并）；
 * 光标移动（字符/词/行首尾、Ctrl+A/E/B/F）；横向滚动半宽规则；按列鼠标定位。
 * 渲染在 {@link SingleLineInputElement}（保持元素≤90 行）。</p>
 */
public final class SingleLineInput {

    private record Snapshot(String value, int cursor) {}

    /** 渲染视窗：可见文本、起始显示列、可见文本内的光标偏移。 */
    public record View(String text, int startColumn, int cursorIndex) {}

    private final String prompt;
    private final String placeholder;
    private final KillRing killRing = new KillRing();
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private String value = "";
    private int cursor;
    private String lastAction;

    /**
     * Creates a single-line input.
     *
     * @param prompt      prompt prefix (e.g. {@code "> "})
     * @param placeholder placeholder shown when empty
     */
    public SingleLineInput(String prompt, String placeholder) {
        this.prompt = prompt == null ? "" : prompt;
        this.placeholder = placeholder == null ? "" : placeholder;
    }

    /** Prompt prefix. */
    public String prompt() {
        return prompt;
    }

    /** Placeholder text. */
    public String placeholder() {
        return placeholder;
    }

    /** Current value. */
    public String value() {
        return value;
    }

    /** Cursor character offset. */
    public int cursor() {
        return cursor;
    }

    /** Replaces the value and clamps the cursor. */
    public void setValue(String next) {
        value = next == null ? "" : next;
        cursor = Math.min(cursor, value.length());
    }

    /** Clears value, cursor, undo history and last-action state. */
    public void clear() {
        value = "";
        cursor = 0;
        undoStack.clear();
        lastAction = null;
    }

    /**
     * Handles one key event.
     *
     * @param event key event
     * @param keys  keybinding table (for platform-neutral matching)
     * @return whether the event was consumed
     */
    public boolean handleKey(KeyEvent event, KeybindingsManager keys) {
        boolean ctrl = event.hasCtrl();
        boolean alt = event.hasAlt();
        if (event.code() == KeyCode.CHAR && !ctrl && !alt) {
            insertText(event.string());
            return true;
        }
        if (ctrl && "a".equals(key(event))) {
            cursor = 0;
            lastAction = null;
            return true;
        }
        if (ctrl && "e".equals(key(event))) {
            cursor = value.length();
            lastAction = null;
            return true;
        }
        if (ctrl && "b".equals(key(event))) {
            moveLeft();
            return true;
        }
        if (ctrl && "f".equals(key(event))) {
            moveRight();
            return true;
        }
        if (ctrl && "k".equals(key(event))) {
            killToEnd();
            return true;
        }
        if (ctrl && "u".equals(key(event))) {
            killToStart();
            return true;
        }
        if (ctrl && "w".equals(key(event)) || alt && "backspace".equals(key(event))) {
            killWordBackward();
            return true;
        }
        if (alt && "d".equals(key(event)) || alt && "delete".equals(key(event))) {
            killWordForward();
            return true;
        }
        if (ctrl && "y".equals(key(event))) {
            yank();
            return true;
        }
        if (alt && "y".equals(key(event))) {
            yankPop();
            return true;
        }
        if (ctrl && "-".equals(key(event))) {
            undo();
            return true;
        }
        if (ctrl && "left".equals(key(event)) || alt && "b".equals(key(event))) {
            moveWordBackward();
            return true;
        }
        if (ctrl && "right".equals(key(event)) || alt && "f".equals(key(event))) {
            moveWordForward();
            return true;
        }
        if (ctrl || alt) {
            return false;
        }
        switch (event.code()) {
            case BACKSPACE -> {
                backspace();
                return true;
            }
            case DELETE -> {
                forwardDelete();
                return true;
            }
            case LEFT -> {
                moveLeft();
                return true;
            }
            case RIGHT -> {
                moveRight();
                return true;
            }
            case HOME -> {
                cursor = 0;
                lastAction = null;
                return true;
            }
            case END -> {
                cursor = value.length();
                lastAction = null;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Inserts raw text (bracketed paste/typing); newlines are dropped, tabs become 4 spaces. */
    public void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        var clean = text.replace("\r\n", "").replace("\r", "")
            .replace("\n", "").replace("\t", "    ");
        if (clean.isEmpty()) {
            return;
        }
        boolean whitespace = clean.length() == 1 && Character.isWhitespace(clean.charAt(0));
        if (whitespace || !"type-word".equals(lastAction)) {
            pushUndo();
        }
        lastAction = "type-word";
        value = value.substring(0, cursor) + clean + value.substring(cursor);
        cursor += clean.length();
    }

    /** Moves the cursor to the character nearest the given display column. */
    public void setCursorByColumn(int col) {
        cursor = TextLayout.charIndexAtColumn(value, Math.max(0, col));
        lastAction = null;
    }

    /** Cursor display column (wide chars count 2). */
    public int cursorColumn() {
        return TextLayout.displayWidth(value.substring(0, cursor));
    }

    /** Total display width of the value. */
    public int totalWidth() {
        return TextLayout.displayWidth(value);
    }

    /**
     * Computes the horizontal scroll window for the given available width
     * (pi {@code input.ts} 半宽规则：光标近端贴边、居中时半宽起滚)。
     *
     * @param availableWidth visible columns after the prompt (at least 1)
     * @return view with visible text, start column and visible cursor index
     */
    public View view(int availableWidth) {
        int width = Math.max(1, availableWidth);
        int total = totalWidth();
        if (total < width) {
            return new View(value, 0, cursor);
        }
        int scrollWidth = cursor == value.length() ? Math.max(1, width - 1) : width;
        int cursorCol = cursorColumn();
        int half = scrollWidth / 2;
        int start;
        if (cursorCol < half) {
            start = 0;
        } else if (cursorCol > total - half) {
            start = Math.max(0, total - scrollWidth);
        } else {
            start = Math.max(0, cursorCol - half);
        }
        String visible = TextLayout.sliceByColumns(value, start, start + scrollWidth);
        int beforeStart = TextLayout.charIndexAtColumn(value, start);
        int cursorIndex = Math.max(0, Math.min(cursor - beforeStart, visible.length()));
        return new View(visible, start, cursorIndex);
    }

    // ── editing operations ─────────────────────────────────────────────

    private String key(KeyEvent event) {
        var cp = event.codePoint();
        if (event.hasCtrl() && cp >= 1 && cp <= 26) {
            return String.valueOf((char) ('a' + cp - 1));
        }
        var text = event.string();
        if (text == null || text.isEmpty()) {
            return "";
        }
        char first = text.charAt(0);
        if (event.hasCtrl() && first >= 1 && first <= 26) {
            return String.valueOf((char) ('a' + first - 1));
        }
        return String.valueOf(Character.toLowerCase(first));
    }

    private void moveLeft() {
        lastAction = null;
        if (cursor > 0) {
            cursor -= Character.charCount(value.codePointBefore(cursor));
        }
    }

    private void moveRight() {
        lastAction = null;
        if (cursor < value.length()) {
            cursor += Character.charCount(value.codePointAt(cursor));
        }
    }

    private void backspace() {
        lastAction = null;
        if (cursor == 0) {
            return;
        }
        pushUndo();
        int from = cursor - Character.charCount(value.codePointBefore(cursor));
        value = value.substring(0, from) + value.substring(cursor);
        cursor = from;
    }

    private void forwardDelete() {
        lastAction = null;
        if (cursor >= value.length()) {
            return;
        }
        pushUndo();
        int to = cursor + Character.charCount(value.codePointAt(cursor));
        value = value.substring(0, cursor) + value.substring(to);
    }

    private void killToStart() {
        if (cursor == 0) {
            return;
        }
        pushUndo();
        killRing.push(value.substring(0, cursor), true, "kill".equals(lastAction));
        value = value.substring(cursor);
        cursor = 0;
        lastAction = "kill";
    }

    private void killToEnd() {
        if (cursor >= value.length()) {
            return;
        }
        pushUndo();
        killRing.push(value.substring(cursor), false, "kill".equals(lastAction));
        value = value.substring(0, cursor);
        lastAction = "kill";
    }

    private void killWordBackward() {
        if (cursor == 0) {
            return;
        }
        boolean wasKill = "kill".equals(lastAction);
        pushUndo();
        int old = cursor;
        moveWordBackward();
        int from = cursor;
        cursor = old;
        killRing.push(value.substring(from, cursor), true, wasKill);
        value = value.substring(0, from) + value.substring(cursor);
        cursor = from;
        lastAction = "kill";
    }

    private void killWordForward() {
        if (cursor >= value.length()) {
            return;
        }
        boolean wasKill = "kill".equals(lastAction);
        pushUndo();
        int old = cursor;
        moveWordForward();
        int to = cursor;
        cursor = old;
        killRing.push(value.substring(cursor, to), false, wasKill);
        value = value.substring(0, cursor) + value.substring(to);
        lastAction = "kill";
    }

    private void yank() {
        var text = killRing.peek();
        if (text == null || text.isEmpty()) {
            return;
        }
        pushUndo();
        value = value.substring(0, cursor) + text + value.substring(cursor);
        cursor += text.length();
        lastAction = "yank";
    }

    private void yankPop() {
        if (!"yank".equals(lastAction) || killRing.length() <= 1) {
            return;
        }
        pushUndo();
        var previous = killRing.peek() == null ? "" : killRing.peek();
        value = value.substring(0, Math.max(0, cursor - previous.length()))
            + value.substring(cursor);
        cursor = Math.max(0, cursor - previous.length());
        killRing.rotate();
        var text = killRing.peek() == null ? "" : killRing.peek();
        value = value.substring(0, cursor) + text + value.substring(cursor);
        cursor += text.length();
        lastAction = "yank";
    }

    private void moveWordBackward() {
        if (cursor == 0) {
            return;
        }
        lastAction = null;
        int i = cursor;
        while (i > 0 && !isWord(value.codePointBefore(i))) {
            i -= Character.charCount(value.codePointBefore(i));
        }
        while (i > 0 && isWord(value.codePointBefore(i))) {
            i -= Character.charCount(value.codePointBefore(i));
        }
        cursor = i;
    }

    private void moveWordForward() {
        if (cursor >= value.length()) {
            return;
        }
        lastAction = null;
        int i = cursor;
        while (i < value.length() && !isWord(value.codePointAt(i))) {
            i += Character.charCount(value.codePointAt(i));
        }
        while (i < value.length() && isWord(value.codePointAt(i))) {
            i += Character.charCount(value.codePointAt(i));
        }
        cursor = i;
    }

    private static boolean isWord(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '_';
    }

    private void pushUndo() {
        undoStack.addLast(new Snapshot(value, cursor));
        if (undoStack.size() > 200) {
            undoStack.removeFirst();
        }
    }

    private void undo() {
        var snapshot = undoStack.pollLast();
        if (snapshot == null) {
            return;
        }
        value = snapshot.value();
        cursor = snapshot.cursor();
        lastAction = null;
    }

    /** Snapshot list for tests. */
    List<String> undoValues() {
        var out = new ArrayList<String>();
        for (var snapshot : undoStack) {
            out.add(snapshot.value());
        }
        return out;
    }
}
