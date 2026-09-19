package com.pijava.tui.screen;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.EntryObserver;
import com.pijava.coding.agent.core.StreamObserver;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.component.MetaKind;
import com.pijava.tui.component.SlashCompleter;
import com.pijava.tui.component.StatusBar;
import com.pijava.tui.component.StatusIndicator;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.tui.event.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Main chat screen: transcript viewport (streaming draft inside the viewport)
 * + editor, plus the status bar (Phase 3 design §7.1, alignment design §5.3).
 * Implements the coding-agent observers; all mutations happen on the render
 * thread via {@code TuiEventDispatcher}.
 */
public final class ChatScreen implements EntryObserver, StreamObserver {

    private final ChatPanel chatPanel = new ChatPanel();
    private final EditorComponent editor = new EditorComponent();
    private SlashCompleter completer = new SlashCompleter(List.of());
    private SessionSnapshot snapshot;
    private final StringBuilder assistantDraft = new StringBuilder();
    private final StringBuilder thinkingDraft = new StringBuilder();
    // 唯一一个激活指示器槽（pi activeStatusIndicator）。volatile：写入在渲染线程，
    // 读取还来自 inline 模式的倒计时唤醒线程（hasTickingIndicator）。
    private volatile StatusIndicator indicator;
    // pi 的取消提示取自用户键位表（keyText("app.interrupt")）；默认值与
    // KeybindingsManager 的默认 app.interrupt 绑定一致，PiTuiApp 构造时改用实际绑定。
    private String interruptHint = "esc";
    // Text of the user message optimistically shown on submit; matched against
    // the transcript entry when the run completes so it isn't duplicated.
    private String pendingUserText;
    // Whether the current run's assistant text was already rendered through
    // the streaming path (TextEnd). Transcript entries must not render it a
    // second time — the transcript snapshot may contain extra blocks (e.g.
    // thinking) or reordered deltas that would otherwise duplicate the bubble.
    //
    // ⚠️ 三个字段都跨线程：写点在 onStreamEvent（会话事件派发线程），读点在
    // onEntry / finishRun（渲染与提交线程）⇒ 需要可见性保证（台账 B45）。
    // volatile 只加可见性、不改语义 —— 单个 boolean 的读写本身无竞态。
    private volatile boolean assistantStreamed;
    // Thinking bubbles are committed at ThinkingEnd; without a TextEnd the
    // transcript entry would otherwise render them a second time.
    private volatile boolean thinkingRendered;
    // Tool calls observed in the current run (drives the turn separator).
    // ⚠️ 用 AtomicInteger 而不是 volatile int：`++` 是读-改-写，
    // volatile 不保证原子性（spotbugs 原文 AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE）。
    private final java.util.concurrent.atomic.AtomicInteger runToolCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Receive a complete transcript entry (dedupes streamed assistant text). */
    @Override
    public void onEntry(Entry entry) {
        if (entry instanceof Entry.Message message
                && "assistant".equals(message.message().role())) {
            // Skip entries already rendered via the streaming path, and empty
            // assistant entries (a failed run commits one with no blocks, which
            // would otherwise render as a blank bubble above the optimistic
            // user message).
            if (assistantStreamed || thinkingRendered
                    || message.message().content().isEmpty()) {
                return;
            }
        }
        if (entry instanceof Entry.Message message
                && "user".equals(message.message().role())
                && pendingUserText != null
                && pendingUserText.equals(joinText(message.message().content()))) {
            pendingUserText = null;
            return;
        }
        var bubble = ChatMessage.from(entry);
        if (bubble != null) {
            chatPanel.append(bubble);
        }
    }

    /** Incremental stream events → draft inside the viewport (typewriter). */
    @Override
    public void onStreamEvent(StreamEvent event) {
        switch (event) {
            case StreamEvent.Start ignored -> {
                assistantStreamed = false;
                thinkingRendered = false;
            }
            case StreamEvent.TextStart ignored -> {
                assistantDraft.setLength(0);
                chatPanel.setDraft(null);
            }
            case StreamEvent.TextDelta(var contentIndex, var delta, var partial) -> {
                assistantDraft.append(delta);
                chatPanel.setDraft(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent(assistantDraft.toString()))));
            }
            case StreamEvent.TextEnd(var contentIndex, var text, var partial) -> {
                chatPanel.append(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent(text))));
                assistantDraft.setLength(0);
                chatPanel.setDraft(null);
                assistantStreamed = true;
            }
            case StreamEvent.ThinkingStart ignored -> thinkingDraft.setLength(0);
            case StreamEvent.ThinkingDelta(var contentIndex, var delta, var partial) -> {
                thinkingDraft.append(delta);
                chatPanel.setDraft(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent("\uD83E\uDDD0 " + thinkingDraft))));
            }
            case StreamEvent.ThinkingEnd(var contentIndex, var thinking, var partial) -> {
                chatPanel.append(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent("\uD83E\uDDD0 " + thinking))));
                thinkingDraft.setLength(0);
                chatPanel.setDraft(null);
                thinkingRendered = true;
            }
            case StreamEvent.ToolCallStart ignored -> runToolCalls.incrementAndGet();
            case StreamEvent.ToolCallDelta ignored -> { }
            case StreamEvent.ToolCallEnd ignored -> { }
            case StreamEvent.UsageInfo ignored -> { }
            case StreamEvent.StreamDone ignored -> { }
            case StreamEvent.StreamError(var reason, var error, var partial) -> {
                // 错误只进聊天区 —— pi 的 footer 没有错误态，错误仅经 showError
                // 落进 chatContainer。这里原先还往状态栏写一条常驻红字，且只写不清
                // ⇒ 双报且多出来的那条永不消失（台账 B37）。
                chatPanel.append(new ChatMessage.Error(
                    reason + (error != null ? ": " + error.getMessage() : "")));
                chatPanel.setDraft(null);
            }
        }
    }


    /**
     * Appends the startup card (Codex-CLI style: version, model, directory,
     * tips) as the first message of the transcript.
     */
    public void showWelcome(String cardText) {
        chatPanel.append(new ChatMessage.System(cardText, MetaKind.GENERIC));
    }

    /** Reset per-run tool accounting (called before each prompt submission). */
    public void resetRunTracking() {
        runToolCalls.set(0);
    }

    /**
     * Appends the inter-turn separator (Codex-CLI style) once the transcript
     * entries have been committed, but only for runs that used tools.
     * Elapsed time is measured from submission to status completion.
     */
    public void finishRun(long elapsedNanos) {
        int calls = runToolCalls.get();   // 读一次，标签里的两处取值必须一致
        if (calls <= 0) {
            return;
        }
        long seconds = Math.max(0, elapsedNanos / 1_000_000_000L);
        String worked = seconds >= 60
            ? "Worked for " + (seconds / 60) + "m " + (seconds % 60) + "s"
            : "Worked for " + seconds + "s";
        String label = worked + " • Local tools: " + calls
            + (calls == 1 ? " call" : " calls");
        chatPanel.append(new ChatMessage.TurnSeparator(label));
    }
    /** Refresh the status bar from a session snapshot. */
    public void updateSnapshot(SessionSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Render the main chat area: transcript viewport, one blank row, then the
     * input row. There is no standalone draft bubble or separator — the draft
     * lives inside the viewport (Codex-CLI style).
     */
    public Column render() {
        var children = new ArrayList<Element>();
        children.add(chatPanel.render().fill());
        children.add(TamboUIAdapter.spacer(1));
        Element popup = completer.render();
        if (popup != null) {
            children.add(popup);
        }
        children.add(editor.render());
        return TamboUIAdapter.column(children);
    }

    /** Committed transcript snapshot for the scrollback printer. */
    public List<ChatMessage> transcriptMessages() {
        return chatPanel.messages();
    }

    /** The in-flight streaming draft for the scrollback printer. */
    public ChatMessage transcriptDraft() {
        return chatPanel.draft();
    }

    /** Editor row count (bottom-region height driver). */
    public int editorLineCount() {
        return editor.lineCount();
    }

    /**
     * Bottom region for regular (raw-scrollback) mode: editor + status bar.
     * The transcript lives in the terminal scrollback, so only this fixed
     * region is redrawn in place.
     */
    public Element renderBottomArea() {
        var children = new ArrayList<Element>();
        Element popup = completer.render();
        if (popup != null) {
            children.add(popup);
        }
        children.add(editor.render());
        children.add(statusBar());
        return TamboUIAdapter.column(children);
    }

    /**
     * Bottom status bar (indicator first, then the snapshot, then an empty row).
     *
     * <p>指示器优先于快照：pi 把重试/压缩的进度放在同一个状态槽里
     * （{@code showStatusIndicator}），状态栏的形状承载不了它（{@code SessionSnapshot}
     * 没有重试状态位，docs/31 §8.38.2-(2)）。</p>
     */
    public Element statusBar() {
        var active = indicator;
        if (active != null) {
            return TamboUIAdapter.text(" " + active.textAt(System.nanoTime())).length(1);
        }
        return snapshot == null
            ? TamboUIAdapter.row().length(1)
            : new StatusBar().render(snapshot);
    }

    // ── 会话事件面（pi interactive-mode.ts:3173-3503 的 switch）──

    /**
     * 会话级事件 → 指示器 / 聊天区。**只在渲染线程被调用**（经 {@code SessionEventChannel}）。
     *
     * <p>覆盖 pi 的五个重试/摘要重试 case，外加压缩指示器的置/清位
     * （pi {@code compaction_start}/{@code compaction_end} 的指示器那一半）。
     * 不做：{@code agent_end} 不看 {@code willRetry}（pi 也不看）、压缩结束时的
     * 聊天区重建与取消消息（台账 B38）、Esc 换绑（pi-java 的 {@code abort()} 已经
     * 先调 {@code abortRetry()}，提示反而是真的）。</p>
     *
     * @param event 会话事件
     */
    public void onSessionEvent(AgentSessionEvent event) {
        switch (event) {
            case AgentSessionEvent.AutoRetryStart start -> setIndicator(
                StatusIndicator.retry(start.attempt(), start.maxAttempts(),
                    start.delayMs(), interruptHint));
            case AgentSessionEvent.AutoRetryEnd ended -> {
                clearIndicator(StatusIndicator.Kind.RETRY);
                if (!ended.success()) {
                    // 成功不报错（正常响应就是答案）；失败才把终局错误写进聊天区。
                    showError("Retry failed after " + ended.attempt() + " attempts: "
                        + (ended.finalError() == null || ended.finalError().isEmpty()
                            ? "Unknown error" : ended.finalError()));
                }
            }
            case AgentSessionEvent.SummarizationRetryScheduled scheduled -> {
                showError(scheduled.errorMessage());
                setIndicator(StatusIndicator.retry(scheduled.attempt(),
                    scheduled.maxAttempts(), scheduled.delayMs(), interruptHint));
            }
            case AgentSessionEvent.SummarizationRetryAttemptStart start -> {
                clearIndicator(StatusIndicator.Kind.RETRY);
                setIndicator("branchSummary".equals(start.source())
                    ? new StatusIndicator.BranchSummary(interruptHint)
                    : new StatusIndicator.Compaction(start.reason(), interruptHint));
            }
            case AgentSessionEvent.SummarizationRetryFinished ignored ->
                clearIndicator(StatusIndicator.Kind.RETRY);
            case AgentSessionEvent.CompactionStart start ->
                setIndicator(new StatusIndicator.Compaction(literalOf(start.reason()), interruptHint));
            case AgentSessionEvent.CompactionEnd ignored ->
                clearIndicator(StatusIndicator.Kind.COMPACTION);
            default -> { }
        }
    }

    /** 置指示器（pi {@code showStatusIndicator}：单槽，旧的直接被替换）。 */
    private void setIndicator(StatusIndicator next) {
        indicator = next;
    }

    /** 枚举 → pi 的线格式字面量（{@code CompactionObserver} 的 {@code "manual"} 等）。 */
    private static String literalOf(AgentSessionEvent.CompactionReason reason) {
        return switch (reason) {
            case MANUAL -> "manual";
            case THRESHOLD -> "threshold";
            case OVERFLOW -> "overflow";
        };
    }

    /**
     * 清指示器（pi {@code clearStatusIndicator}，{@code :2115-2134}）。
     *
     * <p>⚠️ 给了 kind 时 <b>kind 不匹配即 no-op</b> —— 这是 pi 的第一句守卫，照抄：
     * 「摘要重试完成清 retry」不会误清并发中的 compaction 指示器。{@code kind} 为
     * null 表示无条件清（pi 的无参重载）。</p>
     *
     * @param kind 期望清除的种类，或 null
     */
    public void clearIndicator(StatusIndicator.Kind kind) {
        if (kind != null && (indicator == null || indicator.kind() != kind)) {
            return;
        }
        indicator = null;
    }

    /** 是否有含倒计时的指示器（inline 模式的 1 Hz 唤醒据此决定要不要重绘）。 */
    public boolean hasTickingIndicator() {
        var active = indicator;
        return active != null && active.ticking();
    }

    /** The active status indicator, or null (test hook). */
    public StatusIndicator indicator() {
        return indicator;
    }

    /** 取消提示键名（pi 的 {@code keyText("app.interrupt")}，默认 {@code esc}）。 */
    public void setInterruptHint(String hint) {
        if (hint != null && !hint.isEmpty()) {
            this.interruptHint = hint;
        }
    }

    /** 往聊天区追加一条错误（pi {@code showError}，{@code :4273-4277}：红字 + {@code Error: } 前缀）。 */
    private void showError(String message) {
        chatPanel.append(new ChatMessage.Error("Error: " + message));
    }

    /** Forward a key event: slash completion first, then the editor. */
    public void onKeyEvent(KeyEvent event) {
        var action = completer.onKeyEvent(event);
        switch (action) {
            case COMPLETE -> {
                if (applyCompletion()) {
                    completer.update(editor.getText());
                }
            }
            case HANDLED -> { }
            case IGNORED -> {
                editor.onKeyEvent(event);
                completer.update(editor.getText());
            }
        }
    }

    /** Inject the slash-command catalog for completion (from the registry). */
    public void setSlashCommands(List<SlashCompleter.CommandItem> items) {
        completer = new SlashCompleter(items);
        completer.update(editor.getText());
    }

    /** Replace the input with the highlighted command (Tab or Enter). */
    public boolean applyCompletion() {
        String name = completer.selectedName();
        if (name == null) {
            return false;
        }
        editor.replaceText(name);
        completer.update(name);
        return true;
    }

    /** Whether the slash-command popup is visible. */
    public boolean completerActive() {
        return completer.active();
    }

    /** Popup row count (inline bottom-region height driver). */
    public int completerLineCount() {
        return completer.lineCount();
    }

    // ── Viewport navigation (driven by PiTuiApp) ─────────────

    /** Scrolls the transcript by {@code delta} rows (negative = up). */
    public void scrollByRows(int delta) {
        chatPanel.scrollByRows(delta);
    }

    /** Jumps to the top of the transcript. */
    public void scrollToTop() {
        chatPanel.scrollToTop();
    }

    /** Jumps to the bottom of the transcript and resumes follow. */
    public void scrollToBottom() {
        chatPanel.scrollToBottom();
    }

    /** The current first visible row (test hook). */
    public int scrollOffset() {
        return chatPanel.scrollOffset();
    }

    /** The current viewport height in rows (page-scroll driver). */
    public int visibleRows() {
        return chatPanel.visibleRows();
    }

    // ── Input portal API (PiTuiApp only touches ChatScreen) ──

    /** Clears the editor and closes the slash completer. */
    public void clearInput() {
        editor.clear();
        completer.update("");
    }

    public boolean isInputEmpty() {
        return editor.getText().isEmpty();
    }

    /** The current editor text. */
    public String inputText() {
        return editor.getText();
    }

    /** Submit the current editor content through its handler. */
    public void submitInput() {
        editor.submit();
    }

    /** Register the plain-Enter submit callback (agent prompt submission). */
    public void onSubmit(Consumer<String> handler) {
        editor.onSubmit(handler);
    }

    /** Insert a newline (Shift+Enter). */
    public void insertNewline() {
        editor.insertNewline();
    }

    /** Insert pasted text into the editor. */
    public void insertText(String text) {
        editor.insertText(text);
        completer.update(editor.getText());
    }

    /** Append a system/info bubble (slash command results). */
    public void appendSystemText(String text) {
        chatPanel.append(new ChatMessage.System(text, MetaKind.GENERIC));
    }

    /** Show the user's message immediately on submit (optimistic bubble). */
    public void appendUserText(String text) {
        pendingUserText = text;
        chatPanel.append(new ChatMessage.User(text));
    }

    /** The current snapshot (for tree selectors). */
    public SessionSnapshot snapshot() {
        return snapshot;
    }

    /** The last committed message (test hook). */
    public ChatMessage lastMessage() {
        return chatPanel.last();
    }

    /** All committed messages (test hook). */
    public java.util.List<ChatMessage> messages() {
        return chatPanel.messages();
    }

    /** The committed message count (test hook). */
    public int messageCount() {
        return chatPanel.size();
    }

    private static String joinText(List<ContentBlock> blocks) {
        var builder = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent(String text1)) {
                builder.append(text1);
            }
        }
        return builder.toString();
    }

}
