package com.pijava.tui.component;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.EditorElement;

import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
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
 * Alt+Y yank-pop), word navigation (Ctrl+Left/Right, Alt+B/F), the
 * Ctrl+A/E/B/F cursor shortcuts, and prompt history on bare ↑/↓ (K27,
 * {@link EditorHistory}). Undo restores text and cursor via TextAreaState's
 * cursor-move primitives (it has no cursor setter).</p>
 *
 * <p>Kill-ring operations live in {@link EditorKillOps}; large-paste folding
 * (K26) in {@link EditorPaste}; character jump mode (K31) in
 * {@link EditorJump}.</p>
 */
public final class EditorComponent {

    /** Undo snapshot: editor text plus the grapheme-column cursor position. */
    private record Snapshot(String text, int row, int col,
                            Map<Integer, String> pastes, int pasteCounter) {
    }

    private final TextAreaState state = new TextAreaState();
    private final EditorElement element;
    private final EditorKillOps killOps = new EditorKillOps(state, this::pushSnapshot);
    /** 批 34（K27）：提示历史＋↑/↓ 首末行分派（pi editor.ts navigateHistory 对位）。 */
    private final EditorHistory history = new EditorHistory();
    private final ArrayDeque<Snapshot> undoStack = new ArrayDeque<>();
    /** K26：大粘贴折叠注册表（pi pastes/pasteCounter）。 */
    private final EditorPaste paste = new EditorPaste(state);
    /** K31：字符跳转等待态。 */
    private final EditorJump jump = new EditorJump(state);
    private Consumer<String> submitHandler = text -> { };
    /** 批 12 T4（docs/23 §3）：pi {@code editorPaddingX}（editor.ts:523-524
     *  双侧留白对位）——live-read 热更；默认 0。 */
    private java.util.function.IntSupplier paddingX = () -> 0;
    /** L7 W6（C15）：思考等级边框色现读源；默认主题 border。 */
    private Supplier<Style> borderStyleSource = SemanticColors::border;

    /** Creates the editor component backed by a fresh {@link TextAreaState}. */
    public EditorComponent() {
        this.element = TamboUIAdapter.editorElement(state)
            .placeholder("Type a message… (Enter send, Shift+Enter newline)")
            .fill()
            .addClass("EditorComponent");
    }

    /** The input row for the render tree: thinking-colored rules plus the editor. */
    public Element render() {
        // pi editor.ts:523-524/560：双侧 paddingX 留白（clamp 0..3 在 accessor；
        // 这里再夹一次防 live-read 越界）。java 编辑器无鼠标命中面，仅渲染留白；
        // 内容可用宽度随 Row 布局自然收缩。
        int pad = Math.min(3, Math.max(0, paddingX.getAsInt()));
        Row row;
        if (pad == 0) {
            row = TamboUIAdapter.row(
                TamboUIAdapter.text("> ").cyan(),
                element);
        } else {
            row = TamboUIAdapter.row(
                TamboUIAdapter.text(" ".repeat(pad) + "> ").cyan(),
                element,
                TamboUIAdapter.text(" ".repeat(pad)));
        }
        // L7 W6（C15）：上下各一条 thinking 色横线（pi editor.ts borderColor 对位）。
        Element top = new DynamicBorder(borderStyleSource);
        Element bottom = new DynamicBorder(borderStyleSource);
        return TamboUIAdapter.column(top, row, bottom);
    }

    /** L7 W6（C15）：注入 editor border 色现读源（thinking 等级；null ⇒ 主题 border）。 */
    public void setBorderStyleSource(Supplier<Style> source) {
        borderStyleSource = source == null ? SemanticColors::border : source;
    }

    /** L7 W6（C20）：注入硬件光标开关现读源（true ⇒ 不画假光标）。 */
    public void setShowHardwareCursorSource(java.util.function.BooleanSupplier source) {
        element.showHardwareCursor(source);
    }

    /** 注入 paddingX 现读源（{@code uiPrefs().getEditorPaddingX()} 对位）。 */
    public void setPaddingXSource(java.util.function.IntSupplier source) {
        paddingX = source == null ? () -> 0 : source;
    }

    /**
     * 批 17（T4）：对话框内嵌用的<b>裸</b>编辑器元素（无 {@code "> "} 提示列、
     * 无 padding——pi extension-editor.ts 内嵌 {@code Editor} 组件对位）。
     */
    public EditorElement renderBare() {
        return element;
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
        // K31（pi editor.ts:704-722）：jump mode 等待态与触发键最优先。
        if (jump.handleKey(event, stroke)) {
            return;
        }
        // 批 34（K27；pi editor.ts:928/941 对位）：↑/↓ 首末行走提示历史，其余键退出浏览。
        if (history.handleNavigation(event.code(), event.hasCtrl() || event.hasAlt(),
                state, this::pushSnapshot)) {
            return;
        }
        // pi tui.editor.* bindings that do not clash with the app.* shortcuts.
        if (stroke.ctrl() && "w".equals(stroke.key())) {
            killOps.deleteWordBackward();
            return;
        }
        if (stroke.alt() && "backspace".equals(stroke.key())) {
            killOps.deleteWordBackward();
            return;
        }
        if (stroke.alt() && "d".equals(stroke.key())) {
            killOps.deleteWordForward();
            return;
        }
        if (stroke.alt() && "delete".equals(stroke.key())) {
            killOps.deleteWordForward();
            return;
        }
        if (stroke.ctrl() && "u".equals(stroke.key())) {
            killOps.deleteToLineStart();
            return;
        }
        if (stroke.ctrl() && "k".equals(stroke.key())) {
            killOps.deleteToLineEnd();
            return;
        }
        if (stroke.ctrl() && "y".equals(stroke.key())) {
            killOps.yank();
            return;
        }
        if (stroke.alt() && "y".equals(stroke.key())) {
            killOps.yankPop();
            return;
        }
        if (stroke.ctrl() && "-".equals(stroke.key())) {
            undo();
            return;
        }
        if (stroke.ctrl() && "left".equals(stroke.key()) || stroke.alt() && "b".equals(stroke.key())) {
            killOps.moveWordLeft();
            return;
        }
        if (stroke.ctrl() && "right".equals(stroke.key()) || stroke.alt() && "f".equals(stroke.key())) {
            killOps.moveWordRight();
            return;
        }
        // pi cursorLineStart/End（批 34，K20：ctrl+home/end 归还上层视口滚动的
        // 顶/底键；行首行尾只留 ctrl+a/ctrl+e——pi 本锚点同形）。
        if (stroke.ctrl() && "a".equals(stroke.key())) {
            state.moveCursorToLineStart();
            return;
        }
        if (stroke.ctrl() && "e".equals(stroke.key())) {
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
        if (whitespace || !EditorKillOps.TYPE_WORD.equals(killOps.lastAction())) {
            pushSnapshot();
        }
        killOps.markTypeWord();
        state.insert(text);
    }

    private void backspace() {
        if (state.cursorCol() == 0 && state.cursorRow() == 0) {
            return;
        }
        // K26（pi editor.ts:1411-1433）：退格落在完整粘贴标记上 ⇒ 整段删除并重编号。
        if (paste.deleteBackward(this::pushSnapshot)) {
            killOps.clearAction();
            return;
        }
        pushSnapshot();
        killOps.clearAction();
        state.deleteBackward();
    }

    private void forwardDelete() {
        // K26：前向删落在完整粘贴标记起点 ⇒ 整段删除（pi 原子段语义的等价面）。
        if (paste.deleteForward(this::pushSnapshot)) {
            killOps.clearAction();
            return;
        }
        String line = state.getLine(state.cursorRow());
        if (state.cursorRow() == state.lineCount() - 1
                && state.cursorCol() >= EditorWordNav.charToGrapheme(line, line.length())) {
            return;
        }
        pushSnapshot();
        killOps.clearAction();
        state.deleteForward();
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
        state.moveCursorToLineStart();
        for (int i = 0; i < snapshot.col(); i++) {
            state.moveCursorRight();
        }
        // K26（pi editor.ts:2133-2142）：撤销同时恢复粘贴注册表与计数。
        paste.restore(snapshot.pastes(), snapshot.pasteCounter());
        state.ensureCursorVisible(state.lineCount(), 80);
        killOps.clearAction();
    }

    // ── TextAreaState driver helpers ────────────────────────────────────

    private void pushSnapshot() {
        undoStack.addLast(new Snapshot(state.text(), state.cursorRow(), state.cursorCol(),
            paste.snapshotPastes(), paste.counter()));
    }

    // ── Public API (unchanged surface, programmatic edits are undoable) ──

    /** Insert pasted text at the cursor (bracketed paste support; K26 folding). */
    public void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        pushSnapshot();
        killOps.clearAction();
        history.reset();
        paste.insert(text);
    }

    /** Replace the whole editor content (slash completion). */
    public void replaceText(String text) {
        String replacement = text == null ? "" : text;
        if (!state.text().equals(replacement)) {
            pushSnapshot();
        }
        killOps.clearAction();
        history.reset();
        state.setText(replacement);
        paste.clear();
    }

    /** Register the submit callback (invoked by the app on plain Enter). */
    public void onSubmit(Consumer<String> handler) {
        this.submitHandler = handler;
    }

    /** Number of visible editor rows (content rows plus the two border rules). */
    public int lineCount() {
        return Math.max(1, state.lineCount()) + 2;
    }

    /** Current editor text. */
    public String getText() {
        return state.text();
    }

    /** K26：展开后的完整文本（粘贴标记还原；外部编辑器等消费面用）。 */
    public String getExpandedText() {
        return paste.expand(state.text());
    }

    /** Clear the editor and its undo history. */
    public void clear() {
        state.clear();
        undoStack.clear();
        killOps.clearAction();
        history.reset();
        jump.cancel();
        paste.clear();
    }

    /** Replace the editor content (programmatic edits are undoable). */
    public void setText(String text) {
        String replacement = text == null ? "" : text;
        if (!state.text().equals(replacement)) {
            pushSnapshot();
        }
        killOps.clearAction();
        history.reset();
        state.setText(replacement);
        paste.clear();
    }

    /**
     * K25 补全底座：光标所在行的 {@code [0, cursor)} 文本。
     *
     * @return 当前行光标前的字符子串
     */
    public String lineBeforeCursor() {
        String line = state.getLine(state.cursorRow());
        int charCol = Math.min(EditorWordNav.graphemeToChar(line, state.cursorCol()), line.length());
        return line.substring(0, Math.max(0, charCol));
    }

    /**
     * K25 补全底座：删除光标前 {@code graphemes} 个字素后插入 {@code text}
     * （补全替换 token 的原子操作，单次 undo）。
     *
     * @param graphemes 被替换 token 的字素数
     * @param text      替换文本（可为空串）
     */
    public void replaceBeforeCursor(int graphemes, String text) {
        pushSnapshot();
        killOps.clearAction();
        history.reset();
        for (int i = 0; i < graphemes && state.cursorCol() > 0; i++) {
            state.deleteBackward();
        }
        if (text != null && !text.isEmpty()) {
            state.insert(text);
        }
    }

    /** Insert a newline (Shift+Enter). */
    public void insertNewline() {
        pushSnapshot();
        killOps.clearAction();
        history.reset();
        state.insert("\n");
    }

    /** Notify the submit handler with the current content and reset the editor. */
    public void submit() {
        // K26（pi submitValue :1040-1055）：提交前展开粘贴标记并 trim。
        var text = paste.expand(state.text()).trim();
        if (!text.isBlank()) {
            history.add(text);        // pi addToHistory：先入历史再交 handler
            history.reset();
            submitHandler.accept(text);
            state.clear();
            undoStack.clear();
            killOps.clearAction();
            paste.clear();
        }
    }
}
