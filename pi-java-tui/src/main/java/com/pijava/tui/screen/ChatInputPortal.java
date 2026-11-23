package com.pijava.tui.screen;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.component.FileCompleter;
import com.pijava.tui.component.PendingQueueView;
import com.pijava.tui.component.SlashCompleter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 批 29（E2 结构债拆分，ChatScreen 簇⑧⑩纯移动——行为零变化）：输入口面——
 * 编辑器＋slash 补全（键事件先补全后编辑器）、编辑器侧 settings 源注入
 * （留白／补全行数）、输入框读写与提交、dequeue 提示键名。{@code pendingQueue}
 * 本体留 ChatScreen（注入引用）。
 */
final class ChatInputPortal {

    private final EditorComponent editor = new EditorComponent();
    private final PendingQueueView pendingQueue;
    private SlashCompleter completer = new SlashCompleter(List.of());
    /** K25：{@code @文件}／路径补全（pi CombinedAutocompleteProvider 的文件臂）。 */
    private FileCompleter fileCompleter =
        new FileCompleter(Path.of(System.getProperty("user.dir")), () -> 5);
    // 批 12（docs/23）：settings 消费键的 live-read 源（PiTuiApp 装配注入；
    // 默认值保持批 12 前行为——直构 ChatScreen 的测试面零变化）。
    private java.util.function.IntSupplier autocompleteMaxVisible = () -> 5;

    ChatInputPortal(PendingQueueView pendingQueue) {
        this.pendingQueue = pendingQueue;
    }

    /** K25：补全根目录（装配时注入会话 cwd；默认进程工作目录）。 */
    void setCompletionBasePath(Path basePath) {
        fileCompleter = new FileCompleter(basePath, () -> autocompleteMaxVisible.getAsInt());
    }

    /** Forward a key event: slash completion, file completion, then the editor. */
    void onKeyEvent(KeyEvent event) {
        if (fileCompleter.active()) {
            switch (fileCompleter.onKeyEvent(event)) {
                case COMPLETE -> applyFileCompletion();
                case HANDLED -> { }
                case IGNORED -> {
                    editor.onKeyEvent(event);
                    refreshCompleters();
                }
            }
            return;
        }
        var action = completer.onKeyEvent(event);
        switch (action) {
            case COMPLETE -> {
                if (applyCompletion()) {
                    refreshCompleters();
                }
            }
            case HANDLED -> { }
            case IGNORED -> {
                // Tab 未命中 slash 补全 ⇒ 强制文件补全（pi shouldTriggerFileCompletion）。
                if (event.isChar('\t') && !event.hasShift()) {
                    fileCompleter.update(editor.lineBeforeCursor(), true);
                    return;
                }
                editor.onKeyEvent(event);
                refreshCompleters();
            }
        }
    }

    /** Recompute both popups from the current editor text (slash name context wins). */
    private void refreshCompleters() {
        completer.update(editor.getText());
        if (completer.active()) {
            fileCompleter.update(null, false);
        } else {
            fileCompleter.update(editor.lineBeforeCursor(), false);
        }
    }

    /** Inject the slash-command catalog for completion (from the registry). */
    void setSlashCommands(List<SlashCompleter.CommandItem> items) {
        completer = new SlashCompleter(items, autocompleteMaxVisible);
        completer.update(editor.getText());
    }

    /** 批 12 T4：{@code editorPaddingX} 现读源（编辑器双侧留白热更）。 */
    void setEditorPaddingSource(java.util.function.IntSupplier source) {
        editor.setPaddingXSource(source);
    }

    /**
     * 批 12 T5：{@code autocompleteMaxVisible} 现读源——对<b>之后</b>的
     * {@link #setSlashCommands} 重建生效（PiTuiApp 装配时先注入再建目录；
     * 设置 overlay 关闭时的目录重建自然带上新值——pi applyRuntimeSettings
     * 同效的热更时机）。
     */
    void setAutocompleteMaxVisibleSource(java.util.function.IntSupplier source) {
        autocompleteMaxVisible = source == null ? () -> 5 : source;
    }

    /** Replace the input with the highlighted command (Tab or Enter). */
    boolean applyCompletion() {
        if (fileCompleter.active()) {
            return applyFileCompletion();
        }
        String name = completer.selectedName();
        if (name == null) {
            return false;
        }
        editor.replaceText(name);
        completer.update(name);
        return true;
    }

    /** K25：把高亮文件建议替换进编辑器（目录不补尾空格）。 */
    boolean applyFileCompletion() {
        String value = fileCompleter.selectedValue();
        if (value == null) {
            return false;
        }
        int graphemes = fileCompleter.replaceGraphemes();
        String insert = fileCompleter.selectedDirectory() ? value : value + " ";
        editor.replaceBeforeCursor(graphemes, insert);
        fileCompleter.update(null, false);
        completer.update(editor.getText());
        return true;
    }

    /**
     * K41（pi editor.ts:804-830）：Enter 在补全打开时的语义。
     *
     * @return true 表示应继续提交（slash 命令名上下文或无补全）；文件补全只应用不提交
     */
    boolean enterWithCompletion() {
        if (completer.active()) {
            applyCompletion();
            return true;
        }
        if (fileCompleter.active()) {
            applyFileCompletion();
            return false;
        }
        return true;
    }

    /** Whether a completion popup (slash or file) is visible. */
    boolean completerActive() {
        return completer.active() || fileCompleter.active();
    }

    /** Popup row count (inline bottom-region height driver). */
    int completerLineCount() {
        return fileCompleter.active() ? fileCompleter.lineCount() : completer.lineCount();
    }

    /** Editor row count (bottom-region height driver). */
    int editorLineCount() {
        return editor.lineCount();
    }

    /** 编辑器渲染件（ChatScreen render/renderBottomArea 的装配缝——原直读 editor 字段）。 */
    Element editorElement() {
        return editor.render();
    }

    /** 补全弹层渲染件（同上的装配缝——原直读 completer 字段）。 */
    Element completerElement() {
        return fileCompleter.active() ? fileCompleter.render() : completer.render();
    }

    /** Clears the editor and closes the slash completer. */
    void clearInput() {
        editor.clear();
        refreshCompleters();
    }

    boolean isInputEmpty() {
        return editor.getText().isEmpty();
    }

    /** The current editor text. */
    String inputText() {
        return editor.getText();
    }

    /** Submit the current editor content through its handler. */
    void submitInput() {
        editor.submit();
    }

    /** Register the plain-Enter submit callback (agent prompt submission). */
    void onSubmit(Consumer<String> handler) {
        editor.onSubmit(handler);
    }

    /** Insert a newline (Shift+Enter). */
    void insertNewline() {
        editor.insertNewline();
    }

    /** Insert pasted text into the editor. */
    void insertText(String text) {
        editor.insertText(text);
        refreshCompleters();
    }

    /** Replace the editor content（pi {@code editor.setText}；dequeue/fork 回填用，docs/21 §3.3）。 */
    void setEditorText(String text) {
        editor.setText(text);
        refreshCompleters();
    }

    /**
     * pi {@code restoreQueuedMessagesToEditor} 的编辑器侧合并式
     * （interactive-mode.ts:4752-4756 逐字，docs/21 裁决⑦）：
     * {@code [queuedText, currentText].filter(trim).join("\n\n")}——排队在前、
     * 草稿在后、空白段丢弃。dequeue／Esc 流式／树导航三臂共用。
     */
    void restoreToEditor(String queuedText) {
        var combined = java.util.stream.Stream.of(queuedText, editor.getText())
            .filter(t -> !t.isBlank())
            .collect(java.util.stream.Collectors.joining("\n\n"));
        setEditorText(combined);
    }

    /** dequeue 取回提示键名（pi {@code getAppKeyDisplay("app.message.dequeue")}；装配处注入）。 */
    void setDequeueHint(String hint) {
        pendingQueue.dequeueHint(hint);
    }
}
