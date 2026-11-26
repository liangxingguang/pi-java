package com.pijava.tui.screen;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.extension.WorkingIndicatorOptions;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.LoaderSpinner;
import com.pijava.tui.component.MetaKind;
import com.pijava.tui.component.PendingQueueView;
import com.pijava.tui.component.StatusIndicator;

/**
 * 批 29（E2 结构债拆分，ChatScreen 簇⑥纯移动——行为零变化）：会话事件面
 * （pi interactive-mode.ts:3173-3503 的 switch）＋激活指示器单槽＋状态/警告/
 * 错误追加。**只在渲染线程被调用**（经 app 包的 {@code SessionEventChannel}）。
 */
final class SessionEventRouter {

    private final ChatPanel chatPanel;
    private final PendingQueueView pendingQueue;
    private final CacheNoticeRenderer cacheNotices;
    // B46：实时工具卡（pi pendingTools）——toolCallId ⇒ chatPanel 下标；终态后登记
    // 已完成 id，供条目流跳过重复的 tool result 气泡。
    private final java.util.Set<String> liveCompletedToolCalls;
    private final java.util.Map<String, Integer> liveTools = new java.util.HashMap<>();
    // R12：实时工具卡起始时刻（record 不可变 ⇒ update 时从此处取，避免读 ChatPanel）。
    private final java.util.Map<String, Long> toolStartNanos = new java.util.HashMap<>();
    // R13/R28：bash 实时卡（id ⇒ chatPanel 下标）与排队区卡槽（null id 用哨兵键）。
    private final java.util.Map<String, Integer> liveBash = new java.util.HashMap<>();
    // R13：已由流式卡收口的 bash 落盘条目 id（SessionRunner 只走 onEntry，不走 EntryAppended）。
    private final java.util.Set<String> finalizedBashIds = new java.util.HashSet<>();
    private static final String NULL_BASH_ID = "<null-bash-id>";
    // 唯一一个激活指示器槽（pi activeStatusIndicator）。volatile：写入在渲染线程，
    // 读取还来自 inline 模式的倒计时唤醒线程（hasTickingIndicator）。
    private volatile StatusIndicator indicator;
    // pi 的取消提示取自用户键位表（keyText("app.interrupt")）；默认值与
    // KeybindingsManager 的默认 app.interrupt 绑定一致，PiTuiApp 构造时改用实际绑定。
    private String interruptHint = "esc";
    // ── L7 W1（docs/22 §4.3，C6）：working 指示器三态（pi interactive-mode.ts:553-556）──
    // pi defaultWorkingMessage = "Working"（:556 逐字）。
    static final String DEFAULT_WORKING_MESSAGE = "Working";
    // 扩展 setWorkingMessage/setWorkingVisible/setWorkingIndicator 的落点（volatile：
    // 写在扩展调度入队后的渲染线程、读在渲染帧——同上方 indicator 的手法）。
    private volatile String workingMessage;
    private volatile boolean workingVisible = true;
    private volatile WorkingIndicatorOptions workingOptions;
    // 批 15（T2d）：message_end 的 /bug 建议钩（PiTuiApp 装配，门在钩内）。
    private java.util.function.Consumer<com.pijava.ai.message.Message.AssistantMessage>
        bugReportHook = message -> { };
    // docs/26 §4.3（K39）：OSC 7501 状态机（PiTuiLauncher 装配；可 null ⇒ 不报）。
    private com.pijava.tui.util.ProgramStatusReporter programStatus;
    // E2-32：OSC 9;4 进度（PiTuiLauncher 装配；可 null ⇒ 不报）。
    private com.pijava.tui.util.TerminalProgressReporter terminalProgress;
    // B38：压缩成功后的上下文条目现读源与整表重放钩（PiTuiApp / ChatScreen 装配）。
    private java.util.function.Supplier<java.util.List<Entry>> contextEntriesSupplier =
        java.util.List::of;
    private java.util.function.Consumer<java.util.List<Entry>> contextReplay = entries -> { };

    SessionEventRouter(ChatPanel chatPanel, PendingQueueView pendingQueue,
                       CacheNoticeRenderer cacheNotices,
                       java.util.Set<String> liveCompletedToolCalls) {
        this.chatPanel = chatPanel;
        this.pendingQueue = pendingQueue;
        this.cacheNotices = cacheNotices;
        this.liveCompletedToolCalls = liveCompletedToolCalls;
    }

    /**
     * 会话级事件 → 指示器 / 聊天区。**只在渲染线程被调用**（经 {@code SessionEventChannel}）。
     *
     * <p>覆盖 pi 的五个重试/摘要重试 case，外加压缩起止（pi
     * {@code compaction_start}/{@code compaction_end}）的指示器、取消消息、成功后的
     * 整表重建与摘要卡（B38）。{@code agent_end} 不看 {@code willRetry}（pi 也不看）；
     * Esc 换绑在 {@code GlobalKeyRouter}（B39，状态门等价）。</p>
     *
     * @param event 会话事件
     */
    void onSessionEvent(AgentSessionEvent event) {
        var reporter = programStatus;
        if (reporter != null) {
            reporter.handleEvent(event);
        }
        switch (event) {
            case AgentSessionEvent.TurnStart ignored -> {
                // E2-32（pi interactive-mode.ts:3617-3623）：turn_start 起 OSC 9;4。
                var progress = terminalProgress;
                if (progress != null) {
                    progress.start();
                }
                // L7 W1（docs/22 §4.3）：pi turn_start（interactive-mode.ts:3612-3627）逐字——
                // workingVisible 为真且槽内非 working ⇒ 置 working；不可见 ⇒ 全清。
                if (workingVisible) {
                    var active = indicator;
                    if (active == null || active.kind() != StatusIndicator.Kind.WORKING) {
                        setIndicator(workingNow());
                    }
                } else {
                    clearIndicator(null);
                }
            }
            case AgentSessionEvent.AgentEnd ignored -> {
                // E2-32（pi interactive-mode.ts:3845-3850）：agent_end 停 OSC 9;4。
                var progress = terminalProgress;
                if (progress != null) {
                    progress.stop();
                }
                // pi agent_end（:3848）：clearStatusIndicator("working")——kind 守卫
                // ⇒ 不误清并发中的 compaction 指示器。
                clearIndicator(StatusIndicator.Kind.WORKING);
                // pi :3854：agent_end 清空 pendingTools（未收尾的实时卡保留末态）。
                liveTools.clear();
                toolStartNanos.clear();
                liveBash.clear();
            }
            // B46（pi interactive-mode.ts:3797-3842）：实时工具卡。
            case AgentSessionEvent.ToolExecutionStart start -> {
                // 嵌套调用显示在父行内（pi :3798-3799）。
                if (start.parentToolCallId() != null) {
                    break;
                }
                long startNanos = System.nanoTime();
                liveTools.put(start.toolCallId(), chatPanel.size());
                toolStartNanos.put(start.toolCallId(), startNanos);
                chatPanel.append(new ChatMessage.ToolRunning(
                    start.toolName(), String.valueOf(start.args()),
                    java.util.List.of(), false, startNanos));
            }
            case AgentSessionEvent.ToolExecutionUpdate update -> {
                var index = liveTools.get(update.toolCallId());
                if (index != null) {
                    chatPanel.replaceAt(index, new ChatMessage.ToolRunning(
                        update.toolName(), String.valueOf(update.args()),
                        resultContent(update.partialResult()), false,
                        toolStartNanos.get(update.toolCallId())));
                }
            }
            case AgentSessionEvent.ToolExecutionEnd end -> {
                var index = liveTools.remove(end.toolCallId());
                toolStartNanos.remove(end.toolCallId());
                if (index != null) {
                    liveCompletedToolCalls.add(end.toolCallId());
                    chatPanel.replaceAt(index, new ChatMessage.ToolResult(
                        resultContent(end.result()), end.isError(),
                        end.durationMs(), end.toolName()));
                }
            }
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
                    ? new StatusIndicator.BranchSummary(System.nanoTime(), interruptHint)
                    : new StatusIndicator.Compaction(System.nanoTime(),
                        start.reason(), interruptHint));
            }
            case AgentSessionEvent.SummarizationRetryFinished ignored ->
                clearIndicator(StatusIndicator.Kind.RETRY);
            case AgentSessionEvent.CompactionStart start -> {
                // E2-32（pi interactive-mode.ts:3867-3872）：压缩开始起进度。
                var progress = terminalProgress;
                if (progress != null) {
                    progress.start();
                }
                setIndicator(new StatusIndicator.Compaction(
                    System.nanoTime(), start.reason().name().toLowerCase(), interruptHint));
            }
            case AgentSessionEvent.CompactionEnd end -> {
                // E2-32（pi interactive-mode.ts:3887-3892）：压缩结束停进度。
                var progress = terminalProgress;
                if (progress != null) {
                    progress.stop();
                }
                clearIndicator(StatusIndicator.Kind.COMPACTION);
                // B38（pi :3895-3935 逐臂对位）。
                if (end.aborted()) {
                    if (end.reason() == AgentSessionEvent.CompactionReason.MANUAL) {
                        showError("Compaction cancelled");
                    } else {
                        showStatus("Auto-compaction cancelled");
                    }
                } else if (end.result() != null) {
                    var entries = contextEntriesSupplier.get();
                    if (entries.isEmpty() || !(entries.get(0) instanceof Entry.Compaction compaction)) {
                        throw new IllegalStateException(
                            "Completed compaction is missing from the session context");
                    }
                    // pi renderSessionEntries(entries.slice(1))：压缩条目按时间序追加在下方。
                    liveTools.clear();
                    contextReplay.accept(entries.subList(1, entries.size()));
                    chatPanel.append(new ChatMessage.CompactionCard(
                        compaction.tokensBefore(), compaction.summary(),
                        compaction.id(), compaction.timestamp().toString()));
                    if (end.result().usage() != null) {
                        cacheNotices.appendCompactionCostNotice(end.result().usage());
                    }
                } else if (end.errorMessage() != null) {
                    if (end.reason() == AgentSessionEvent.CompactionReason.MANUAL) {
                        showError(end.errorMessage());
                    } else {
                        // pi :3928-3930：自动压缩错误只追加红字，不加 "Error: " 前缀。
                        chatPanel.append(new ChatMessage.Error(end.errorMessage()));
                    }
                }
            }
            // R13/R28：bash 输出增量 → 聊天区实时卡 + 排队区卡槽；终态由 EntryAppended 收口。
            case AgentSessionEvent.BashExecutionUpdate bash ->
                handleBashUpdate(bash.id(), bash.delta());
            case AgentSessionEvent.QueueUpdate q ->
                pendingQueue.update(q.steering(), q.followUp());
            case AgentSessionEvent.FlashStatus status -> showStatus(status.message());
            // 批 13（T10，pi :3444-3451 对位）：暖条目即时上屏（appendUsage 收口
            // 广播的 EntryAppended）；记 id——run 结束 onEntry 投递时跳过。
            case AgentSessionEvent.EntryAppended appended -> {
                if (appended.entry() instanceof Entry.Usage usage
                        && "cache_warm".equals(usage.kind())) {
                    cacheNotices.appendUsageNotice(usage);
                    cacheNotices.markLiveNotice(usage.id());
                }
                // R13/R28：bash 终局条目到达 ⇒ 把实时卡换成终态 BashCard、清排队区卡槽。
                if (appended.entry() instanceof Entry.BashExecution bash) {
                    finalizeBash(bash);
                }
            }
            // 批 13（T11 即时臂，pi :4185-4191＋:3593 挂点对位——message_end 先于
            // 落盘，tracker 只见过已投递条目 ⇒「entries 未含 message」天然成立）。
            case AgentSessionEvent.MessageEnd end -> {
                if (end.message() instanceof com.pijava.ai.message.Message.AssistantMessage am) {
                    if (am.stopReason() != com.pijava.ai.message.StopReason.ABORTED
                            && am.stopReason() != com.pijava.ai.message.StopReason.ERROR) {
                        // L5 W7（pi :3783 挂点）：withheld reasoning 提示先于 miss 通知。
                        cacheNotices.appendReasoningDropNotice(am);
                        var miss = cacheNotices.detectOnMessageEnd(am);
                        if (miss.isPresent() && cacheNotices.noticesEnabled()) {
                            cacheNotices.appendCacheMissNotice(miss.get());
                            cacheNotices.markLastMissNotified(am); // onEntry 同实例去重
                        }
                    }
                    // 批 15（T2d，pi :3586 挂点对位）：/bug 建议（门在 PiTuiApp）。
                    bugReportHook.accept(am);
                }
            }
            // 扩展处理器抛错：上聊天区警告（pi 同族 showWarning；文案非模型可见）。
            case AgentSessionEvent.ExtensionError error -> {
                var payload = error.error();
                showWarning("Extension " + payload.extensionName() + " error in "
                    + payload.event() + ": " + payload.message());
            }
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
    void showWarning(String message) {
        chatPanel.append(new ChatMessage.System("Warning: " + message, MetaKind.GENERIC));
    }

    /** 置指示器（pi {@code showStatusIndicator}：单槽，旧的直接被替换）。 */
    private void setIndicator(StatusIndicator next) {
        indicator = next;
    }

    // ── L7 W1（docs/22 §4.3）：working 三态与复位 ────────────────

    /** 按当前三态构造 working 指示器（pi {@code showWorkingStatusIndicator}，:2546-2559）。 */
    private StatusIndicator workingNow() {
        var options = workingOptions;
        var frames = options != null && options.frames() != null
            ? options.frames() : LoaderSpinner.DEFAULT_FRAMES;
        long interval = options != null && options.intervalMs() != null
            ? options.intervalMs() : LoaderSpinner.DEFAULT_INTERVAL_MS;
        return new StatusIndicator.Working(
            workingMessage != null ? workingMessage : DEFAULT_WORKING_MESSAGE,
            frames, interval, System.nanoTime());
    }

    /**
     * 扩展 {@code setWorkingMessage}（pi :2840-2846）：null ⇒ 回默认；有活动 working
     * 时直接生效（record 不可变 ⇒ 替换新 Working、保留动画起点）。
     */
    void setWorkingMessage(String message) {
        this.workingMessage = message;
        var active = indicator;
        if (active instanceof StatusIndicator.Working w) {
            indicator = new StatusIndicator.Working(
                message != null ? message : DEFAULT_WORKING_MESSAGE,
                w.frames(), w.intervalMs(), w.startNanos());
        }
    }

    /**
     * 扩展 {@code setWorkingVisible}（pi {@code setWorkingVisible}，:2561-2572）：
     * false ⇒ 清 working（kind 守卫）；true 且槽内非 working ⇒ 置（无 streaming
     * 状态位——turn_start/AgentEnd 已是 streaming 真相源，此处对位 pi 的
     * 「isStreaming && 非 working」半边）。
     */
    void setWorkingVisible(boolean visible) {
        this.workingVisible = visible;
        if (!visible) {
            clearIndicator(StatusIndicator.Kind.WORKING);
            return;
        }
        var active = indicator;
        if (active == null || active.kind() != StatusIndicator.Kind.WORKING) {
            setIndicator(workingNow());
        }
    }

    /** 扩展 {@code setWorkingIndicator}（pi :2574-2581）：活动 working 直接换帧表。 */
    void setWorkingIndicator(WorkingIndicatorOptions options) {
        this.workingOptions = options;
        var active = indicator;
        if (active instanceof StatusIndicator.Working w) {
            var frames = options != null && options.frames() != null
                ? options.frames() : LoaderSpinner.DEFAULT_FRAMES;
            long interval = options != null && options.intervalMs() != null
                ? options.intervalMs() : LoaderSpinner.DEFAULT_INTERVAL_MS;
            indicator = new StatusIndicator.Working(w.message(), frames, interval, w.startNanos());
        }
    }

    /**
     * 扩展 UI 复位（pi {@code resetExtensionUI} 的 working 段，:2673-2682 逐字序）：
     * message/visible 回默认 → {@code setWorkingIndicator()}（帧表回默认，动画重启）→
     * 活动 working ⇒ message 换成 {@code "Working (esc to interrupt)"}（pi 原样 quirk）。
     */
    void resetWorkingState() {
        workingMessage = null;
        workingVisible = true;
        workingOptions = null;
        var active = indicator;
        if (active instanceof StatusIndicator.Working ignored) {
            indicator = new StatusIndicator.Working(
                DEFAULT_WORKING_MESSAGE + " (" + interruptHint + " to interrupt)",
                LoaderSpinner.DEFAULT_FRAMES, LoaderSpinner.DEFAULT_INTERVAL_MS,
                System.nanoTime());
        }
    }

    /**
     * 置分支摘要指示器（docs/18 §3.11；pi {@code showStatusIndicator(
     * new BranchSummaryStatusIndicator)}，interactive-mode.ts:5640）。
     */
    void showBranchSummaryIndicator() {
        setIndicator(new StatusIndicator.BranchSummary(System.nanoTime(), interruptHint));
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
    void clearIndicator(StatusIndicator.Kind kind) {
        if (kind != null && (indicator == null || indicator.kind() != kind)) {
            return;
        }
        indicator = null;
    }

    /** 是否有含倒计时的指示器（inline 模式的 1 Hz 唤醒据此决定要不要重绘）。 */
    boolean hasTickingIndicator() {
        var active = indicator;
        return active != null && active.ticking();
    }

    /** The active status indicator, or null (test hook). */
    StatusIndicator indicator() {
        return indicator;
    }

    /** 取消提示键名（pi 的 {@code keyText("app.interrupt")}，默认 {@code esc}）。 */
    void setInterruptHint(String hint) {
        if (hint != null && !hint.isEmpty()) {
            this.interruptHint = hint;
        }
    }

    /** 批 15（T2d）：message_end 的 /bug 建议钩（pi :3586 挂点；门在钩内）。 */
    void setBugReportHook(
            java.util.function.Consumer<com.pijava.ai.message.Message.AssistantMessage> hook) {
        bugReportHook = hook == null ? message -> { } : hook;
    }

    /** docs/26 §4.3（K39）：接线 OSC 7501 状态机。 */
    void setProgramStatusReporter(com.pijava.tui.util.ProgramStatusReporter reporter) {
        this.programStatus = reporter;
    }

    /** E2-32：接线 OSC 9;4 进度（PiTuiLauncher 装配）。 */
    void setTerminalProgressReporter(
            com.pijava.tui.util.TerminalProgressReporter reporter) {
        this.terminalProgress = reporter;
    }

    /** B38：压缩成功的上下文条目现读源（PiTuiApp 装配；会话切换后现读）。 */
    void setContextEntriesSupplier(
            java.util.function.Supplier<java.util.List<Entry>> supplier) {
        this.contextEntriesSupplier = supplier == null ? java.util.List::of : supplier;
    }

    /** B38：压缩成功的整表重放钩（ChatScreen 装配到 TranscriptProjector）。 */
    void setContextReplay(java.util.function.Consumer<java.util.List<Entry>> replay) {
        this.contextReplay = replay == null ? entries -> { } : replay;
    }

    /** 往聊天区追加一条错误（pi {@code showError}，{@code :4273-4277}：红字 + {@code Error: } 前缀）。 */
    void showError(String message) {
        chatPanel.append(new ChatMessage.Error("Error: " + message));
    }

    /** B46：工具结果载荷 → 内容块（非 ToolResult 载荷 ⇒ 空表）。 */
    private static java.util.List<com.pijava.ai.message.ContentBlock> resultContent(Object result) {
        return result instanceof com.pijava.agent.tool.ToolResult<?> toolResult
            ? toolResult.content() : java.util.List.of();
    }

    // ── R13/R28：bash 流式增量 ────────────────────────────────

    /** bash 增量：首块追加实时卡，后续块原地累积；排队区卡槽挂完整文本。 */
    private void handleBashUpdate(String id, String delta) {
        var key = id == null ? NULL_BASH_ID : id;
        var index = liveBash.get(key);
        if (index == null) {
            index = chatPanel.size();
            liveBash.put(key, index);
            chatPanel.append(new ChatMessage.BashRunning("", delta, false));
        } else {
            var prev = chatPanel.messages().get(index);
            var output = prev instanceof ChatMessage.BashRunning b ? b.output() + delta : delta;
            var command = prev instanceof ChatMessage.BashRunning b ? b.command() : "";
            var excluded = prev instanceof ChatMessage.BashRunning b && b.excludeFromContext();
            chatPanel.replaceAt(index, new ChatMessage.BashRunning(command, output, excluded));
        }
        var live = chatPanel.messages().get(index);
        if (live instanceof ChatMessage.BashRunning b) {
            pendingQueue.clearRunningCards();
            pendingQueue.addRunningCard(com.pijava.tui.util.TamboUIAdapter.markupText(
                "[dim]$ " + b.command() + "[/]"));
        }
    }

    /** 生产 onEntry 路径：流式 bash 卡原地收口，返回 true ⇒ 投影器跳过重复卡。 */
    boolean finalizeBashFromEntry(Entry.BashExecution bash) {
        if (finalizedBashIds.contains(bash.id())) { return true; }
        var key = bash.id() == null ? NULL_BASH_ID : bash.id();
        var streamed = liveBash.containsKey(key) || !liveBash.isEmpty();
        if (streamed) { finalizedBashIds.add(bash.id()); finalizeBash(bash); }
        return streamed;
    }

    /** bash 终局条目：实时卡 → 终态 BashCard；清排队区卡槽。 */
    private void finalizeBash(Entry.BashExecution bash) {
        // 按输出反查 liveBash（end 事件的 id 与 start 的 id 由 agent 侧透传一致）；
        // 若未命中（如扩展直接落盘）则不退化为双卡。
        var key = bash.id() == null ? NULL_BASH_ID : bash.id();
        var index = liveBash.remove(key);
        if (index == null && !liveBash.isEmpty()) {
            // 落盘条目的 id 与流式 id 不一致时取首个未结卡（单卡场景）。
            var first = liveBash.entrySet().iterator().next();
            index = liveBash.remove(first.getKey());
        }
        if (index != null) {
            chatPanel.replaceAt(index, new ChatMessage.BashCard(bash.command(), bash.output(),
                bash.exitCode(), bash.cancelled(), bash.truncated(), bash.fullOutputPath(),
                Boolean.TRUE.equals(bash.excludeFromContext())));
            pendingQueue.removeRunningCard();
        }
    }
}
