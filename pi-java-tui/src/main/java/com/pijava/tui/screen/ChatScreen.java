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
import com.pijava.tui.component.PendingQueueView;
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
    private final PendingQueueView pendingQueue = new PendingQueueView();
    private final EditorComponent editor = new EditorComponent();
    // 批 12（docs/23）：settings 消费键的 live-read 源（PiTuiApp 装配注入；
    // 默认值保持批 12 前行为——直构 ChatScreen 的测试面零变化）。
    private java.util.function.IntSupplier outputPad = () -> 0;
    private java.util.function.IntSupplier autocompleteMaxVisible = () -> 5;
    // 批 13（docs/24 T10/T11）：showCacheMissNotices 门（pi interactive-mode.ts
    // :4111/:4185 逐字——晚绑定现读，pi 每次渲染现查同）＋即时臂已上屏的暖条目 id
    // （java 增量追加特有的去重——pi 是重建式渲染，无此问题）。
    private java.util.function.BooleanSupplier showCacheMissNotices = () -> false;
    private final java.util.Set<String> liveUsageNoticeIds = new java.util.HashSet<>();
    // 批 13（T11）：miss 检测的增量跟踪器（裁决⑤）＋即时臂已通知的消息（onEntry
    // 同实例去重——pi 无此问题：pi 重建式渲染；java 是增量追加）。
    private com.pijava.tui.util.CacheMissTracker cacheMissTracker =
        com.pijava.tui.util.CacheMissTracker.noModels();
    private com.pijava.ai.message.Message.AssistantMessage lastMissNotifiedMessage;
    // 批 15（T2d）：message_end 的 /bug 建议钩（PiTuiApp 装配，门在钩内）。
    private java.util.function.Consumer<com.pijava.ai.message.Message.AssistantMessage>
        bugReportHook = message -> { };
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
        // 批 13（T11）：先 feed tracker 再渲染——assistant 气泡被 streaming 去重
        // 跳过也要 feed（条目客观存在；pi scan 同样遍历全量）。miss 通知在条目
        // 自身气泡<b>之后</b>注入（pi :4062-4065 的序）。
        var trackedMiss = cacheMissTracker.feed(entry);
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
                && pendingUserText.equals(ChatMessage.joinText(message.message().content()))) {
            pendingUserText = null;
            return;
        }
        // 批 13（T10）：usage 条目走门控收口（pi :4111）；即时臂已上屏的跳过。
        if (entry instanceof Entry.Usage usage) {
            if (!liveUsageNoticeIds.contains(usage.id())) {
                appendUsageNotice(usage);
            }
            return;
        }
        // 批 12 T6：outputPad append 时烘焙（D62 不回溯已渲染条目）。
        var bubble = ChatMessage.from(entry, outputPad.getAsInt());
        if (bubble != null) {
            chatPanel.append(bubble);
        }
        // 批 13（T11）：条目流臂 miss 通知（pi :4062-4065 注入点对位）——
        // 即时臂已通知的同实例跳过；aborted/error 不注入（pi 同门）。
        if (trackedMiss.isPresent() && showCacheMissNotices.getAsBoolean()
                && entry instanceof Entry.Message messageEntry
                && messageEntry.message() instanceof com.pijava.ai.message.Message.AssistantMessage am
                && am != lastMissNotifiedMessage
                && am.stopReason() != com.pijava.ai.message.StopReason.ABORTED
                && am.stopReason() != com.pijava.ai.message.StopReason.ERROR) {
            appendCacheMissNotice(trackedMiss.get());
        }
    }

    /**
     * 批 13（T10，pi interactive-mode.ts:4110-4115 对位）：暖用量行的唯一收口——
     * 门控（{@code showCacheMissNotices} 晚绑定现读）＋渲染（from 是纯投影，
     * 门在调用点，批 12 T6 的 pad 参数同构）。非 cache_warm kind 由 from 滤掉。
     */
    private void appendUsageNotice(Entry.Usage usage) {
        if (!showCacheMissNotices.getAsBoolean()) {
            return;
        }
        var bubble = ChatMessage.from(usage, outputPad.getAsInt());
        if (bubble != null) {
            chatPanel.append(bubble);
        }
    }

    /**
     * 批 13（T11，pi interactive-mode.ts:4193-4206 逐字）：miss 通知行——
     * 门：{@code missedTokens<20_000 且 missedCost<0.1 ⇒ 不显示}；label 三态
     * （模型切换／idle 超 TTL／普通）。（pi 用 warning 主题色＋前置 Spacer；java
     * System 气泡无主题/Spacer 面——与既有 System 通知同格，风格差随 R 域既有口径。）
     */
    private void appendCacheMissNotice(com.pijava.coding.agent.core.CacheStats.CacheMiss miss) {
        if (miss.missedTokens() < 20_000 && miss.missedCost() < 0.1) {
            return;
        }
        var cost = miss.missedCost() >= 0.01
            ? " (~$" + String.format(java.util.Locale.ROOT, "%.2f", miss.missedCost()) + ")"
            : "";
        var reBilled = com.pijava.coding.agent.core.CacheWarmingFormatting.formatTokens(
            miss.missedTokens()) + " tokens re-billed" + cost;
        String label = "Cache miss";
        if (miss.modelChanged()) {
            label = "Cache miss after model switch";
        } else if (miss.idleMs() >= com.pijava.coding.agent.core.CacheStats.CACHE_TTL_MS) {
            label = "Cache miss after " + Math.round(miss.idleMs() / 60_000.0) + "m idle";
        }
        chatPanel.append(new ChatMessage.System(label + ": " + reBilled, MetaKind.GENERIC));
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
            case StreamEvent.StreamError err -> {
                // 错误只进聊天区 —— pi 的 footer 没有错误态，错误仅经 showError 落进 chatContainer。
                // 这里原先还往状态栏写一条常驻红字，且只写不清 ⇒ 双报且永不消失（台账 B37）。
                var text = StreamEvent.StreamError.textOf(err);
                chatPanel.append(new ChatMessage.Error(
                    err.reason() + (text != null ? ": " + text : "")));
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
        // 批 17（T6）：header 槽在聊天区上方（pi headerContainer 槽位序对位）。
        children.addAll(headerRows());
        children.add(chatPanel.render().fill());
        Element pending = pendingQueue.render();
        if (pending != null) {
            children.add(pending);
        }
        Element popup = completer.render();
        if (popup != null) {
            children.add(popup);
        }
        // 批 17（T6）：widget 上区＝编辑器上（pi 槽位序逐字；空区保留 Spacer(1)
        // —— 替位批 17 前这里的手写 spacer(1)，视觉不变）。
        children.addAll(widgetRows(widgetsAbove, true, true));
        children.add(editor.render());
        children.addAll(widgetRows(widgetsBelow, false, false));
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
        // 批 17（T6，窄差 D101）：inline 的 header 槽渲染在底部位顶部
        //（pi 槽位序为聊天上方；java inline 无聊天容器）。
        children.addAll(headerRows());
        Element popup = completer.render();
        if (popup != null) {
            children.add(popup);
        }
        children.addAll(widgetRows(widgetsAbove, true, true));
        children.add(editor.render());
        children.addAll(widgetRows(widgetsBelow, false, false));
        children.add(statusBar());
        return TamboUIAdapter.column(children);
    }

    /**
     * 批 17（T6）：扩展注入层在 inline 底部位的行数（高度驱动——
     * {@code PiTuiApp.updateBottomHeight} 的加项）。
     */
    public int extensionAreaLineCount() {
        int lines = headerRows().size()
            + widgetRows(widgetsAbove, true, true).size()
            + widgetRows(widgetsBelow, false, false).size();
        if (!extensionStatuses.isEmpty()) {
            lines++;
        }
        if (customFooter != null) {
            lines += customFooterRows().size() - 1;    // 替换内置一行 ⇒ 净增行数
        }
        return lines;
    }

    /**
     * Bottom status bar (indicator first, then the snapshot, then an empty row).
     *
     * <p>指示器优先于快照：pi 把重试/压缩的进度放在同一个状态槽里
     * （{@code showStatusIndicator}），状态栏的形状承载不了它（{@code SessionSnapshot}
     * 没有重试状态位，原 docs/31 §8.38.2-(2)）。</p>
     */
    public Element statusBar() {
        // 批 17（T6）：扩展状态段在状态行上方（pi footer.ts:289-298 的段序对位）；
        // 自定义 footer 替换内置 StatusBar（pi setExtensionFooter 对位）；
        // 指示器仍最优先（既有语义不动）。
        var children = new ArrayList<Element>();
        var statusLine = extensionStatusLine(Math.max(1, terminalWidth.getAsInt()));
        if (statusLine != null) {
            children.add(statusLine);
        }
        var active = indicator;
        if (active != null) {
            children.add(TamboUIAdapter.text(" " + active.textAt(System.nanoTime())).length(1));
        } else if (customFooter != null) {
            children.addAll(customFooterRows());
        } else {
            children.add(snapshot == null
                ? TamboUIAdapter.row().length(1)
                : new StatusBar().render(snapshot));
        }
        return children.size() == 1 ? children.get(0) : TamboUIAdapter.column(children);
    }

    // ── 批 17（T6，docs/28 §1.5）：扩展注入层（widget/footer/header/扩展状态段）──

    /** pi widget 条目两臂（interactive-mode.ts:2413-2436）：文本行集 | 扩展组件
     * （包级——同包测试的断言面）。 */
    sealed interface WidgetEntry {
        /** 文本行臂（截断标记行另计——渲染期定样式）。 */
        record Lines(java.util.List<String> lines, boolean truncated) implements WidgetEntry {}
        /** 组件工厂臂（已实例化）。 */
        record Comp(com.pijava.coding.agent.extension.ExtensionComponent component)
                implements WidgetEntry {}
    }

    /** pi {@code MAX_WIDGET_LINES = 10}（interactive-mode.ts:2486）。 */
    static final int MAX_WIDGET_LINES = 10;

    /** pi extensionWidgetsAbove/Below（:2398 族；LinkedHashMap＝pi Map 插入序）。 */
    private final java.util.Map<String, WidgetEntry> widgetsAbove = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, WidgetEntry> widgetsBelow = new java.util.LinkedHashMap<>();
    /** 自定义 footer/header（null ⇒ 内置/空槽；pi customFooter/customHeader 对位）。 */
    private com.pijava.coding.agent.extension.ExtensionComponent customFooter;
    private com.pijava.coding.agent.extension.ExtensionComponent customHeader;
    /** footer 扩展状态段（pi footerDataProvider.setExtensionStatus 对位；跨线程 ⇒ 并发表）。 */
    private final java.util.Map<String, String> extensionStatuses =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 终端列数源（widget/header/footer 组件 render(width)＋状态行截断；PiTuiApp 注入）。 */
    private java.util.function.IntSupplier terminalWidth = () -> 80;

    /**
     * pi {@code setExtensionWidget}（interactive-mode.ts:2398-2438 逐字序）：
     * placement 默认 aboveEditor；同 key 两区先清（dispose 旧组件臂）；
     * {@code content == null} ⇒ 清＋重排（渲染现读 map，重排天然）；
     * 文本行臂截断 {@link #MAX_WIDGET_LINES}＋{@code "... (widget truncated)"}
     * （muted，文案逐字【模型不可见】）；工厂臂调工厂得组件。
     */
    public void setExtensionWidget(String key,
                                   com.pijava.coding.agent.extension.ExtensionWidgetContent content,
                                   com.pijava.coding.agent.extension.ExtensionWidgetOptions options,
                                   com.pijava.coding.agent.extension.ExtensionUiHostEnv env) {
        removeWidget(widgetsAbove, key);
        removeWidget(widgetsBelow, key);
        if (content == null) {
            return;
        }
        WidgetEntry entry;
        if (content instanceof com.pijava.coding.agent.extension.ExtensionWidgetContent.Lines lines) {
            var text = new ArrayList<>(lines.lines());
            boolean truncated = text.size() > MAX_WIDGET_LINES;
            if (truncated) {
                text = new ArrayList<>(text.subList(0, MAX_WIDGET_LINES));
                text.add("... (widget truncated)");
            }
            entry = new WidgetEntry.Lines(java.util.List.copyOf(text), truncated);
        } else {
            var factory =
                ((com.pijava.coding.agent.extension.ExtensionWidgetContent.Factory) content).factory();
            entry = new WidgetEntry.Comp(factory.create(env));
        }
        String placement = options != null && options.placement() != null
            ? options.placement()
            : com.pijava.coding.agent.extension.ExtensionWidgetOptions.ABOVE_EDITOR;
        (com.pijava.coding.agent.extension.ExtensionWidgetOptions.BELOW_EDITOR.equals(placement)
            ? widgetsBelow : widgetsAbove).put(key, entry);
    }

    /** pi {@code removeExisting}（:2403-2407）：组件臂 dispose 后删键。 */
    private static void removeWidget(java.util.Map<String, WidgetEntry> map, String key) {
        var existing = map.remove(key);
        if (existing instanceof WidgetEntry.Comp comp) {
            try {
                comp.component().dispose();
            } catch (Exception ignored) {
                // pi：dispose 异常不外溢（custom 关闭路径的 try/catch 同旨）
            }
        }
    }

    /**
     * pi {@code setExtensionFooter}（:2524-2549 逐字序）：dispose 旧 custom ⇒ 清槽 ⇒
     * 有工厂挂新、无则回内置 ⇒ 标脏（渲染现读字段，标脏经 dispatcher wake 自然发生）。
     */
    public void setExtensionFooter(
            com.pijava.coding.agent.extension.ExtensionFooterFactory factory,
            com.pijava.coding.agent.extension.ExtensionUiHostEnv env) {
        disposeQuietly(customFooter);
        customFooter = factory == null ? null
            : factory.create(env, new com.pijava.coding.agent.extension.FooterDataView(
                () -> null,     // git 分支：java footer 数据本无 provider（窄差并入 D100）
                () -> java.util.Map.copyOf(extensionStatuses)));
    }

    /**
     * pi {@code setExtensionHeader}（:2551-2595）：java 无常驻内置 header
     * （WelcomeOverlay 是转录消息）⇒ 内置臂＝空，set(null)＝清槽（窄差 D100 族注记）；
     * dispose 旧 custom 逐字。
     */
    public void setExtensionHeader(
            com.pijava.coding.agent.extension.ExtensionSlotFactory factory,
            com.pijava.coding.agent.extension.ExtensionUiHostEnv env) {
        disposeQuietly(customHeader);
        customHeader = factory == null ? null : factory.create(env);
    }

    /** pi {@code footerDataProvider.setExtensionStatus}（:2300-2302）：undefined＝删键，否则覆盖。 */
    public void setExtensionStatus(String key, String text) {
        if (text == null) {
            extensionStatuses.remove(key);
        } else {
            extensionStatuses.put(key, text);
        }
    }

    /**
     * pi {@code resetExtensionUI} 的 ChatScreen 段（interactive-mode.ts:2452-2483）：
     * 清 widget 两区（组件臂 dispose）⇒ footer/header 回内置 ⇒ 清扩展状态段。
     */
    public void resetExtensionUi() {
        for (var key : java.util.List.copyOf(widgetsAbove.keySet())) {
            removeWidget(widgetsAbove, key);
        }
        for (var key : java.util.List.copyOf(widgetsBelow.keySet())) {
            removeWidget(widgetsBelow, key);
        }
        disposeQuietly(customFooter);
        customFooter = null;
        disposeQuietly(customHeader);
        customHeader = null;
        extensionStatuses.clear();
    }

    private static void disposeQuietly(
            com.pijava.coding.agent.extension.ExtensionComponent component) {
        if (component != null) {
            try {
                component.dispose();
            } catch (Exception ignored) {
                // pi 逐字：dispose 异常吞掉
            }
        }
    }

    /** 终端列数源注入（PiTuiApp 装配）。 */
    public void setTerminalWidthSource(java.util.function.IntSupplier source) {
        terminalWidth = source == null ? () -> 80 : source;
    }

    /** widget 区行集（pi renderWidgetContainer :2491-2516 的 spacer 序逐字）。 */
    private java.util.List<Element> widgetRows(java.util.Map<String, WidgetEntry> widgets,
                                               boolean spacerWhenEmpty, boolean leadingSpacer) {
        var rows = new ArrayList<Element>();
        if (widgets.isEmpty()) {
            if (spacerWhenEmpty) {
                rows.add(TamboUIAdapter.spacer(1));
            }
            return rows;
        }
        if (leadingSpacer) {
            rows.add(TamboUIAdapter.spacer(1));
        }
        int width = Math.max(1, terminalWidth.getAsInt());
        for (var entry : widgets.values()) {
            if (entry instanceof WidgetEntry.Lines lines) {
                for (int i = 0; i < lines.lines().size(); i++) {
                    var line = lines.lines().get(i);
                    // 截断标记行 muted（pi ThemedText theme.fg("muted",…) 对位）。
                    boolean marker = lines.truncated() && i == lines.lines().size() - 1;
                    var escaped = com.pijava.tui.util.TextLayout.escapeMarkup(line);
                    rows.add(TamboUIAdapter.markupText(
                        marker ? "[gray]" + escaped + "[/]" : escaped));
                }
            } else {
                for (var line : ((WidgetEntry.Comp) entry).component().render(width)) {
                    rows.add(TamboUIAdapter.markupText(line));   // colorize 产物＝markup
                }
            }
        }
        return rows;
    }

    /** 扩展状态行（pi footer.ts:289-298 逐字）：key 字典序 ⇒ sanitize ⇒ join(" ")
     *  ⇒ 超宽截断＋dim "..."；空表不渲染。 */
    private Element extensionStatusLine(int width) {
        var text = extensionStatusLineText(width);
        return text == null ? null
            : TamboUIAdapter.markupText(
                "[dim]" + com.pijava.tui.util.TextLayout.escapeMarkup(text) + "[/]");
    }

    /** 状态行文本（测试断言面；空表 ⇒ null）。 */
    String extensionStatusLineText(int width) {
        if (extensionStatuses.isEmpty()) {
            return null;
        }
        var sorted = extensionStatuses.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .map(e -> sanitizeStatusText(e.getValue()))
            .toList();
        return UserMessageSelectorScreen.truncateToWidth(
            String.join(" ", sorted), Math.max(1, width));
    }

    /** pi {@code sanitizeStatusText}（footer.ts:14-20 逐字）：[\r\n\t]→空格→多空格折叠→trim。 */
    static String sanitizeStatusText(String text) {
        return text.replaceAll("[\\r\\n\\t]", " ").replaceAll(" +", " ").trim();
    }

    /** 自定义 footer 行集（null ⇒ 调用方回内置）。 */
    private java.util.List<Element> customFooterRows() {
        if (customFooter == null) {
            return java.util.List.of();
        }
        var rows = new ArrayList<Element>();
        for (var line : customFooter.render(Math.max(1, terminalWidth.getAsInt()))) {
            rows.add(TamboUIAdapter.markupText(line));
        }
        return rows;
    }

    /** header 槽行集（null/空 ⇒ 无行）。 */
    private java.util.List<Element> headerRows() {
        if (customHeader == null) {
            return java.util.List.of();
        }
        var rows = new ArrayList<Element>();
        for (var line : customHeader.render(Math.max(1, terminalWidth.getAsInt()))) {
            rows.add(TamboUIAdapter.markupText(line));
        }
        return rows;
    }

    /** 测试探针：扩展状态段的现读面。 */
    public java.util.Map<String, String> extensionStatusesView() {
        return java.util.Map.copyOf(extensionStatuses);
    }

    /** 测试探针：widget 区内容（上/下）。 */
    public java.util.Map<String, WidgetEntry> widgetsForProbe(boolean above) {
        return java.util.Map.copyOf(above ? widgetsAbove : widgetsBelow);
    }

    /** 测试探针：自定义 footer 是否在槽。 */
    public boolean hasCustomFooter() {
        return customFooter != null;
    }

    /** 测试探针：自定义 header 是否在槽。 */
    public boolean hasCustomHeader() {
        return customHeader != null;
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
                setIndicator(new StatusIndicator.Compaction(
                    start.reason().name().toLowerCase(), interruptHint));
            case AgentSessionEvent.CompactionEnd ignored ->
                clearIndicator(StatusIndicator.Kind.COMPACTION);
            case AgentSessionEvent.QueueUpdate q ->
                pendingQueue.update(q.steering(), q.followUp());
            case AgentSessionEvent.FlashStatus status -> showStatus(status.message());
            // 批 13（T10，pi :3444-3451 对位）：暖条目即时上屏（appendUsage 收口
            // 广播的 EntryAppended）；记 id——run 结束 onEntry 投递时跳过。
            case AgentSessionEvent.EntryAppended appended -> {
                if (appended.entry() instanceof Entry.Usage usage
                        && "cache_warm".equals(usage.kind())) {
                    appendUsageNotice(usage);
                    liveUsageNoticeIds.add(usage.id());
                }
            }
            // 批 13（T11 即时臂，pi :4185-4191＋:3593 挂点对位——message_end 先于
            // 落盘，tracker 只见过已投递条目 ⇒「entries 未含 message」天然成立）。
            case AgentSessionEvent.MessageEnd end -> {
                if (end.message() instanceof com.pijava.ai.message.Message.AssistantMessage am) {
                    if (am.stopReason() != com.pijava.ai.message.StopReason.ABORTED
                            && am.stopReason() != com.pijava.ai.message.StopReason.ERROR) {
                        var miss = cacheMissTracker.detectOnMessageEnd(am);
                        if (miss.isPresent() && showCacheMissNotices.getAsBoolean()) {
                            appendCacheMissNotice(miss.get());
                            lastMissNotifiedMessage = am; // onEntry 同实例去重
                        }
                    }
                    // 批 15（T2d，pi :3586 挂点对位）：/bug 建议（门在 PiTuiApp）。
                    bugReportHook.accept(am);
                }
            }
            // docs/14（窄差 D5）：extension_error 已上 RPC 线；TUI 通知面归 D′ 域，本批先忽略。
            case AgentSessionEvent.ExtensionError ignored -> { }
            default -> { }
        }
    }

    /**
     * Append a dim status line to the chat area（B175，原 docs/26 §3.6；
     * pi {@code showStatus}，interactive-mode.ts:3773-3790）。
     */
    private void showStatus(String message) {
        chatPanel.append(new ChatMessage.System(message, MetaKind.GENERIC));
    }

    /**
     * 批 17（T4，docs/28 §1.3）：pi {@code showWarning}（interactive-mode.ts:4629-4633）
     * ——「Warning: 」前缀（文案逐字【模型不可见】）；风格沿既有 System 通知格
     * （R 域口径，批 13 同先例）。
     */
    public void showWarning(String message) {
        chatPanel.append(new ChatMessage.System("Warning: " + message, MetaKind.GENERIC));
    }

    /** 置指示器（pi {@code showStatusIndicator}：单槽，旧的直接被替换）。 */
    private void setIndicator(StatusIndicator next) {
        indicator = next;
    }

    /**
     * 置分支摘要指示器（docs/18 §3.11；pi {@code showStatusIndicator(
     * new BranchSummaryStatusIndicator)}，interactive-mode.ts:5640）。
     */
    public void showBranchSummaryIndicator() {
        setIndicator(new StatusIndicator.BranchSummary(interruptHint));
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
    public void showError(String message) {
        chatPanel.append(new ChatMessage.Error("Error: " + message));
    }

    /**
     * 导航后整表重建（docs/18 §3.11；pi {@code chatContainer.clear()}＋
     * {@code renderInitialMessages()}，interactive-mode.ts:5671-5672）：
     * 清面板＋草稿，按新叶 {@code pathToLeaf} 逐条 {@link ChatMessage#from} 重放。
     *
     * @param entries 会话全量 entries（{@code AgentSession.accumulatedEntries()}）
     * @param leafId  导航后的当前叶（null ⇒ 空转录）
     */
    public void rebuildTranscript(java.util.List<Entry> entries, String leafId) {
        chatPanel.clear();
        assistantDraft.setLength(0);
        thinkingDraft.setLength(0);
        chatPanel.setDraft(null);
        pendingUserText = null;
        assistantStreamed = false;
        thinkingRendered = false;
        // 批 13（T10/T11）：重建即新一轮增量流——去重集与 miss 跟踪器归零。
        liveUsageNoticeIds.clear();
        cacheMissTracker.reset();
        if (leafId != null) {
            for (var e : com.pijava.agent.session.ContextEntries.pathToLeaf(entries, leafId)) {
                // 批 13（T11 重建臂，pi :3999-4065 对位）：逐条 feed，assistant
                // 渲染后注入其 miss（aborted/error 不注入，即时臂同实例去重）。
                var trackedMiss = cacheMissTracker.feed(e);
                // 批 13（T10）：usage 条目同走门控收口。
                if (e instanceof Entry.Usage usage) {
                    appendUsageNotice(usage);
                    continue;
                }
                var bubble = ChatMessage.from(e, outputPad.getAsInt());
                if (bubble != null) {
                    chatPanel.append(bubble);
                }
                if (trackedMiss.isPresent() && showCacheMissNotices.getAsBoolean()
                        && e instanceof Entry.Message messageEntry
                        && messageEntry.message()
                                instanceof com.pijava.ai.message.Message.AssistantMessage am
                        && am != lastMissNotifiedMessage
                        && am.stopReason() != com.pijava.ai.message.StopReason.ABORTED
                        && am.stopReason() != com.pijava.ai.message.StopReason.ERROR) {
                    appendCacheMissNotice(trackedMiss.get());
                }
            }
        }
        scrollToBottom();
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
        completer = new SlashCompleter(items, autocompleteMaxVisible);
        completer.update(editor.getText());
    }

    // ── 批 12（docs/23）：settings 消费键 live-read 源（PiTuiApp 注入）──

    /** 批 12 T6：{@code outputPad} 现读源（append 时烘焙，D62 不回溯）。 */
    public void setOutputPadSource(java.util.function.IntSupplier source) {
        outputPad = source == null ? () -> 0 : source;
    }

    /** 批 12 T4：{@code editorPaddingX} 现读源（编辑器双侧留白热更）。 */
    public void setEditorPaddingSource(java.util.function.IntSupplier source) {
        editor.setPaddingXSource(source);
    }

    /**
     * 批 12 T5：{@code autocompleteMaxVisible} 现读源——对<b>之后</b>的
     * {@link #setSlashCommands} 重建生效（PiTuiApp 装配时先注入再建目录；
     * 设置 overlay 关闭时的目录重建自然带上新值——pi applyRuntimeSettings
     * 同效的热更时机）。
     */
    public void setAutocompleteMaxVisibleSource(java.util.function.IntSupplier source) {
        autocompleteMaxVisible = source == null ? () -> 5 : source;
    }

    /** 批 13（T10/T11）：{@code showCacheMissNotices} 现读源（pi 每次渲染现查同）。 */
    public void setShowCacheMissNoticesSupplier(java.util.function.BooleanSupplier supplier) {
        showCacheMissNotices = supplier == null ? () -> false : supplier;
    }

    /** 批 13（T11）：miss 检测的目录查询源（provider+"/"+model ⇒ {@code catalog.find}）。 */
    public void setCacheMissTrackerModels(
            java.util.function.Function<String, java.util.Optional<com.pijava.ai.catalog.ModelInfo>> models) {
        cacheMissTracker = new com.pijava.tui.util.CacheMissTracker(
            models == null ? key -> java.util.Optional.empty() : models);
    }

    /** 批 15（T2d）：message_end 的 /bug 建议钩（pi :3586 挂点；门在钩内）。 */
    public void setBugReportHook(
            java.util.function.Consumer<com.pijava.ai.message.Message.AssistantMessage> hook) {
        bugReportHook = hook == null ? message -> { } : hook;
    }

    /** 批 12 T11：{@code fullscreenScrollbar} 三态现读源（渲染期热更）。 */
    public void setScrollbarModeSource(java.util.function.Supplier<String> mode) {
        chatPanel.scrollbarMode(() -> switch (mode.get()) {
            case "hidden" -> com.pijava.tui.component.ChatViewportElement.ScrollbarMode.HIDDEN;
            case "always" -> com.pijava.tui.component.ChatViewportElement.ScrollbarMode.ALWAYS;
            default -> com.pijava.tui.component.ChatViewportElement.ScrollbarMode.AUTO;
        });
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

    /** Replace the editor content（pi {@code editor.setText}；dequeue/fork 回填用，docs/21 §3.3）。 */
    public void setEditorText(String text) {
        editor.setText(text);
        completer.update(editor.getText());
    }

    /**
     * pi {@code restoreQueuedMessagesToEditor} 的编辑器侧合并式
     * （interactive-mode.ts:4752-4756 逐字，docs/21 裁决⑦）：
     * {@code [queuedText, currentText].filter(trim).join("\n\n")}——排队在前、
     * 草稿在后、空白段丢弃。dequeue／Esc 流式／树导航三臂共用。
     */
    public void restoreToEditor(String queuedText) {
        var combined = java.util.stream.Stream.of(queuedText, editor.getText())
            .filter(t -> !t.isBlank())
            .collect(java.util.stream.Collectors.joining("\n\n"));
        setEditorText(combined);
    }

    /** dequeue 取回提示键名（pi {@code getAppKeyDisplay("app.message.dequeue")}；装配处注入）。 */
    public void setDequeueHint(String hint) {
        pendingQueue.dequeueHint(hint);
    }

    /** Append a system/info bubble (slash command results). */
    public void appendSystemText(String text) {
        chatPanel.append(new ChatMessage.System(text, MetaKind.GENERIC));
    }

    /**
     * 批 25（docs/36 T8）：markdown 源气泡（pi 的 {@code Markdown} 组件对位，
     * /hotkeys interactive-mode.ts:6921）——消费面＝/help /hotkeys 等
     * markdown 源命令结果。
     */
    public void appendMarkdownText(String text) {
        chatPanel.append(new ChatMessage.Markdown(text));
    }

    /** Show the user's message immediately on submit (optimistic bubble). */
    public void appendUserText(String text) {
        pendingUserText = text;
        // 批 12 T6 注记：乐观气泡<b>不</b>烘焙 pad（设计消费面＝from(entry) 链
        // 三处；提交后的条目经 pendingUserText 去重本就不再渲染，无跳动面）。
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

}
