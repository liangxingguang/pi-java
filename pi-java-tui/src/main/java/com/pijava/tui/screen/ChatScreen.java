package com.pijava.tui.screen;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.EntryObserver;
import com.pijava.coding.agent.core.StreamObserver;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.PendingQueueView;
import com.pijava.tui.component.SlashCompleter;
import com.pijava.tui.component.StatusIndicator;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.tui.event.KeyEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * Main chat screen: transcript viewport (streaming draft inside the viewport)
 * + editor, plus the status bar (Phase 3 design §7.1, alignment design §5.3).
 * Implements the coding-agent observers; all mutations happen on the render
 * thread via {@code TuiEventDispatcher}.
 *
 * <p>批 29（E2 结构债拆分，行为零变化纯移动）：扩展注入层
 * （{@link ExtensionInjectionLayer}）、会话事件面（{@link SessionEventRouter}）、
 * cache 通知收口（{@link CacheNoticeRenderer}）、输入口面（{@link ChatInputPortal}）、
 * 转录投影核（{@link TranscriptProjector}）、渲染装配（{@link ChatScreenLayout}）
 * 移出成件；本类为装配根＋对外委托面——public/包私有 API 签名全保留
 * （约 30 个测试类直接 new ChatScreen）。</p>
 */
public final class ChatScreen implements EntryObserver, StreamObserver {

    private final ChatPanel chatPanel = new ChatPanel();
    private final PendingQueueView pendingQueue = new PendingQueueView();
    // 批 12（docs/23）：settings 消费键的 live-read 源（PiTuiApp 装配注入；
    // 默认值保持批 12 前行为——直构 ChatScreen 的测试面零变化）。
    private java.util.function.IntSupplier outputPad = () -> 0;
    // 批 29（E2）：责任件（字段初始化仅持引用——outputPad 先声明，供应器现读）。
    private final CacheNoticeRenderer cacheNotices =
        new CacheNoticeRenderer(chatPanel, () -> outputPad.getAsInt());
    private final ExtensionInjectionLayer extensionLayer = new ExtensionInjectionLayer();
    private final SessionEventRouter sessionEventRouter =
        new SessionEventRouter(chatPanel, pendingQueue, cacheNotices);
    private final ChatInputPortal inputPortal = new ChatInputPortal(pendingQueue);
    private final TranscriptProjector projector =
        new TranscriptProjector(chatPanel, () -> outputPad.getAsInt(), cacheNotices);
    private final ChatScreenLayout layout =
        new ChatScreenLayout(chatPanel, pendingQueue, extensionLayer, inputPortal,
            sessionEventRouter);

    /** Receive a complete transcript entry (dedupes streamed assistant text). */
    @Override
    public void onEntry(Entry entry) {
        projector.onEntry(entry);
    }

    /** Incremental stream events → draft inside the viewport (typewriter). */
    @Override
    public void onStreamEvent(StreamEvent event) {
        projector.onStreamEvent(event);
    }

    /**
     * Appends the startup card (Codex-CLI style: version, model, directory,
     * tips) as the first message of the transcript.
     */
    public void showWelcome(String cardText) {
        projector.showWelcome(cardText);
    }

    /** Reset per-run tool accounting (called before each prompt submission). */
    public void resetRunTracking() {
        projector.resetRunTracking();
    }

    /**
     * Appends the inter-turn separator (Codex-CLI style) once the transcript
     * entries have been committed, but only for runs that used tools.
     * Elapsed time is measured from submission to status completion.
     */
    public void finishRun(long elapsedNanos) {
        projector.finishRun(elapsedNanos);
    }

    /**
     * 导航后整表重建（docs/18 §3.11；pi {@code chatContainer.clear()}＋
     * {@code renderInitialMessages()}，interactive-mode.ts:5671-5672——批 29 起
     * 本体在 {@link TranscriptProjector}）。
     *
     * @param entries 会话全量 entries（{@code AgentSession.accumulatedEntries()}）
     * @param leafId  导航后的当前叶（null ⇒ 空转录）
     */
    public void rebuildTranscript(java.util.List<Entry> entries, String leafId) {
        projector.rebuildTranscript(entries, leafId);
    }

    /** Show the user's message immediately on submit (optimistic bubble). */
    public void appendUserText(String text) {
        projector.appendUserText(text);
    }

    // ── 批 29（E2）：渲染装配委托（本体在 {@link ChatScreenLayout}）──

    /**
     * Render the main chat area: transcript viewport, one blank row, then the
     * input row. There is no standalone draft bubble or separator — the draft
     * lives inside the viewport (Codex-CLI style).
     */
    public Column render() {
        return layout.render();
    }

    /**
     * Bottom region for regular (raw-scrollback) mode: editor + status bar.
     * The transcript lives in the terminal scrollback, so only this fixed
     * region is redrawn in place.
     */
    public Element renderBottomArea() {
        return layout.renderBottomArea();
    }

    /**
     * Bottom status bar (indicator first, then the snapshot, then an empty row)——
     * 批 29 起本体在 {@link ChatScreenLayout}。
     */
    public Element statusBar() {
        return layout.statusBar();
    }

    /** Refresh the status bar from a session snapshot. */
    public void updateSnapshot(SessionSnapshot snapshot) {
        layout.updateSnapshot(snapshot);
    }

    /** The current snapshot (for tree selectors). */
    public SessionSnapshot snapshot() {
        return layout.snapshot();
    }

    /** Committed transcript snapshot for the scrollback printer. */
    public List<ChatMessage> transcriptMessages() {
        return layout.transcriptMessages();
    }

    /** The in-flight streaming draft for the scrollback printer. */
    public ChatMessage transcriptDraft() {
        return layout.transcriptDraft();
    }

    // ── Viewport navigation (driven by PiTuiApp) ─────────────

    /** Scrolls the transcript by {@code delta} rows (negative = up). */
    public void scrollByRows(int delta) {
        layout.scrollByRows(delta);
    }

    /** Jumps to the top of the transcript. */
    public void scrollToTop() {
        layout.scrollToTop();
    }

    /** Jumps to the bottom of the transcript and resumes follow. */
    public void scrollToBottom() {
        layout.scrollToBottom();
    }

    /** The current first visible row (test hook). */
    public int scrollOffset() {
        return layout.scrollOffset();
    }

    /** The current viewport height in rows (page-scroll driver). */
    public int visibleRows() {
        return layout.visibleRows();
    }

    /** Append a system/info bubble (slash command results). */
    public void appendSystemText(String text) {
        layout.appendSystemText(text);
    }

    /**
     * 批 25（docs/36 T8）：markdown 源气泡（pi 的 {@code Markdown} 组件对位，
     * /hotkeys interactive-mode.ts:6921）——消费面＝/help /hotkeys 等
     * markdown 源命令结果。
     */
    public void appendMarkdownText(String text) {
        layout.appendMarkdownText(text);
    }

    /** The last committed message (test hook). */
    public ChatMessage lastMessage() {
        return layout.lastMessage();
    }

    /** All committed messages (test hook). */
    public java.util.List<ChatMessage> messages() {
        return layout.messages();
    }

    /** The committed message count (test hook). */
    public int messageCount() {
        return layout.messageCount();
    }

    // ── 批 29（E2）：扩展注入层委托（本体在 {@link ExtensionInjectionLayer}）──

    /** pi {@code setExtensionWidget}（interactive-mode.ts:2398-2438 逐字序）。 */
    public void setExtensionWidget(String key,
                                   com.pijava.coding.agent.extension.ExtensionWidgetContent content,
                                   com.pijava.coding.agent.extension.ExtensionWidgetOptions options,
                                   com.pijava.coding.agent.extension.ExtensionUiHostEnv env) {
        extensionLayer.setExtensionWidget(key, content, options, env);
    }

    /** pi {@code setExtensionFooter}（:2524-2549 逐字序）。 */
    public void setExtensionFooter(
            com.pijava.coding.agent.extension.ExtensionFooterFactory factory,
            com.pijava.coding.agent.extension.ExtensionUiHostEnv env) {
        extensionLayer.setExtensionFooter(factory, env);
    }

    /** pi {@code setExtensionHeader}（:2551-2595）。 */
    public void setExtensionHeader(
            com.pijava.coding.agent.extension.ExtensionSlotFactory factory,
            com.pijava.coding.agent.extension.ExtensionUiHostEnv env) {
        extensionLayer.setExtensionHeader(factory, env);
    }

    /** pi {@code footerDataProvider.setExtensionStatus}（:2300-2302）：undefined＝删键，否则覆盖。 */
    public void setExtensionStatus(String key, String text) {
        extensionLayer.setExtensionStatus(key, text);
    }

    /** pi {@code resetExtensionUI} 的 ChatScreen 段（interactive-mode.ts:2452-2483）。 */
    public void resetExtensionUi() {
        extensionLayer.resetExtensionUi();
    }

    /** 终端列数源注入（PiTuiApp 装配）。 */
    public void setTerminalWidthSource(java.util.function.IntSupplier source) {
        extensionLayer.setTerminalWidthSource(source);
    }

    /**
     * 批 17（T6）：扩展注入层在 inline 底部位的行数（高度驱动——
     * {@code PiTuiApp.updateBottomHeight} 的加项）。
     */
    public int extensionAreaLineCount() {
        return extensionLayer.extensionAreaLineCount();
    }

    /** 状态行文本（测试断言面；空表 ⇒ null）。 */
    String extensionStatusLineText(int width) {
        return extensionLayer.extensionStatusLineText(width);
    }

    /** pi {@code sanitizeStatusText}（footer.ts:14-20 逐字）：[\r\n\t]→空格→多空格折叠→trim。 */
    static String sanitizeStatusText(String text) {
        return ExtensionInjectionLayer.sanitizeStatusText(text);
    }

    /** 测试探针：扩展状态段的现读面。 */
    public java.util.Map<String, String> extensionStatusesView() {
        return extensionLayer.extensionStatusesView();
    }

    /** 测试探针：widget 区内容（上/下）。 */
    public java.util.Map<String, ExtensionInjectionLayer.WidgetEntry> widgetsForProbe(boolean above) {
        return extensionLayer.widgetsForProbe(above);
    }

    /** 测试探针：自定义 footer 是否在槽。 */
    public boolean hasCustomFooter() {
        return extensionLayer.hasCustomFooter();
    }

    /** 测试探针：自定义 header 是否在槽。 */
    public boolean hasCustomHeader() {
        return extensionLayer.hasCustomHeader();
    }

    // ── 批 29（E2）：会话事件面委托（本体在 {@link SessionEventRouter}）──

    /**
     * 会话级事件 → 指示器 / 聊天区。**只在渲染线程被调用**（经 {@code SessionEventChannel}）——
     * 批 29 起本体在 {@link SessionEventRouter}（pi interactive-mode.ts:3173-3503 的 switch）。
     *
     * @param event 会话事件
     */
    public void onSessionEvent(AgentSessionEvent event) {
        sessionEventRouter.onSessionEvent(event);
    }

    /** 批 17（T4）：pi {@code showWarning}（interactive-mode.ts:4629-4633）——「Warning: 」前缀。 */
    public void showWarning(String message) {
        sessionEventRouter.showWarning(message);
    }

    /** 置分支摘要指示器（docs/18 §3.11；pi interactive-mode.ts:5640）。 */
    public void showBranchSummaryIndicator() {
        sessionEventRouter.showBranchSummaryIndicator();
    }

    /**
     * 清指示器（pi {@code clearStatusIndicator}，{@code :2115-2134}）——给了 kind 时
     * kind 不匹配即 no-op；null 表示无条件清。
     *
     * @param kind 期望清除的种类，或 null
     */
    public void clearIndicator(StatusIndicator.Kind kind) {
        sessionEventRouter.clearIndicator(kind);
    }

    /** 是否有含倒计时的指示器（inline 模式的 1 Hz 唤醒据此决定要不要重绘）。 */
    public boolean hasTickingIndicator() {
        return sessionEventRouter.hasTickingIndicator();
    }

    /** The active status indicator, or null (test hook). */
    public StatusIndicator indicator() {
        return sessionEventRouter.indicator();
    }

    /** 取消提示键名（pi 的 {@code keyText("app.interrupt")}，默认 {@code esc}）。 */
    public void setInterruptHint(String hint) {
        sessionEventRouter.setInterruptHint(hint);
    }

    /** 批 15（T2d）：message_end 的 /bug 建议钩（pi :3586 挂点；门在钩内）。 */
    public void setBugReportHook(
            java.util.function.Consumer<com.pijava.ai.message.Message.AssistantMessage> hook) {
        sessionEventRouter.setBugReportHook(hook);
    }

    /** 往聊天区追加一条错误（pi {@code showError}，{@code :4273-4277}：红字 + {@code Error: } 前缀）。 */
    public void showError(String message) {
        sessionEventRouter.showError(message);
    }

    // ── 批 12/13：settings 消费键 live-read 源（PiTuiApp 注入）──

    /** 批 12 T6：{@code outputPad} 现读源（append 时烘焙，D62 不回溯）。 */
    public void setOutputPadSource(java.util.function.IntSupplier source) {
        outputPad = source == null ? () -> 0 : source;
    }

    /** 批 13（T10/T11）：{@code showCacheMissNotices} 现读源（pi 每次渲染现查同）。 */
    public void setShowCacheMissNoticesSupplier(java.util.function.BooleanSupplier supplier) {
        cacheNotices.setShowCacheMissNoticesSupplier(supplier);
    }

    /** 批 13（T11）：miss 检测的目录查询源（provider+"/"+model ⇒ {@code catalog.find}）。 */
    public void setCacheMissTrackerModels(
            java.util.function.Function<String, java.util.Optional<com.pijava.ai.catalog.ModelInfo>> models) {
        cacheNotices.setCacheMissTrackerModels(models);
    }

    /** 批 12 T11：{@code fullscreenScrollbar} 三态现读源（渲染期热更）。 */
    public void setScrollbarModeSource(java.util.function.Supplier<String> mode) {
        chatPanel.scrollbarMode(() -> switch (mode.get()) {
            case "hidden" -> com.pijava.tui.component.ChatViewportElement.ScrollbarMode.HIDDEN;
            case "always" -> com.pijava.tui.component.ChatViewportElement.ScrollbarMode.ALWAYS;
            default -> com.pijava.tui.component.ChatViewportElement.ScrollbarMode.AUTO;
        });
    }

    // ── 批 29（E2）：输入口面委托（本体在 {@link ChatInputPortal}）──

    /** Forward a key event: slash completion first, then the editor. */
    public void onKeyEvent(KeyEvent event) {
        inputPortal.onKeyEvent(event);
    }

    /** Inject the slash-command catalog for completion (from the registry). */
    public void setSlashCommands(List<SlashCompleter.CommandItem> items) {
        inputPortal.setSlashCommands(items);
    }

    /** 批 12 T4：{@code editorPaddingX} 现读源（编辑器双侧留白热更）。 */
    public void setEditorPaddingSource(java.util.function.IntSupplier source) {
        inputPortal.setEditorPaddingSource(source);
    }

    /** 批 12 T5：{@code autocompleteMaxVisible} 现读源（对之后的目录重建生效）。 */
    public void setAutocompleteMaxVisibleSource(java.util.function.IntSupplier source) {
        inputPortal.setAutocompleteMaxVisibleSource(source);
    }

    /** Replace the input with the highlighted command (Tab or Enter). */
    public boolean applyCompletion() {
        return inputPortal.applyCompletion();
    }

    /** Whether the slash-command popup is visible. */
    public boolean completerActive() {
        return inputPortal.completerActive();
    }

    /** Popup row count (inline bottom-region height driver). */
    public int completerLineCount() {
        return inputPortal.completerLineCount();
    }

    /** Editor row count (bottom-region height driver). */
    public int editorLineCount() {
        return inputPortal.editorLineCount();
    }

    // ── Input portal API (PiTuiApp only touches ChatScreen) ──

    /** Clears the editor and closes the slash completer. */
    public void clearInput() {
        inputPortal.clearInput();
    }

    public boolean isInputEmpty() {
        return inputPortal.isInputEmpty();
    }

    /** The current editor text. */
    public String inputText() {
        return inputPortal.inputText();
    }

    /** Submit the current editor content through its handler. */
    public void submitInput() {
        inputPortal.submitInput();
    }

    /** Register the plain-Enter submit callback (agent prompt submission). */
    public void onSubmit(Consumer<String> handler) {
        inputPortal.onSubmit(handler);
    }

    /** Insert a newline (Shift+Enter). */
    public void insertNewline() {
        inputPortal.insertNewline();
    }

    /** Insert pasted text into the editor. */
    public void insertText(String text) {
        inputPortal.insertText(text);
    }

    /** Replace the editor content（pi {@code editor.setText}；dequeue/fork 回填用，docs/21 §3.3）。 */
    public void setEditorText(String text) {
        inputPortal.setEditorText(text);
    }

    /**
     * pi {@code restoreQueuedMessagesToEditor} 的编辑器侧合并式
     * （interactive-mode.ts:4752-4756 逐字，docs/21 裁决⑦）——排队在前、
     * 草稿在后、空白段丢弃。dequeue／Esc 流式／树导航三臂共用。
     */
    public void restoreToEditor(String queuedText) {
        inputPortal.restoreToEditor(queuedText);
    }

    /** dequeue 取回提示键名（pi {@code getAppKeyDisplay("app.message.dequeue")}；装配处注入）。 */
    public void setDequeueHint(String hint) {
        inputPortal.setDequeueHint(hint);
    }

}
