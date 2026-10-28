package com.pijava.tui.screen;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
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
    // 唯一一个激活指示器槽（pi activeStatusIndicator）。volatile：写入在渲染线程，
    // 读取还来自 inline 模式的倒计时唤醒线程（hasTickingIndicator）。
    private volatile StatusIndicator indicator;
    // pi 的取消提示取自用户键位表（keyText("app.interrupt")）；默认值与
    // KeybindingsManager 的默认 app.interrupt 绑定一致，PiTuiApp 构造时改用实际绑定。
    private String interruptHint = "esc";
    // 批 15（T2d）：message_end 的 /bug 建议钩（PiTuiApp 装配，门在钩内）。
    private java.util.function.Consumer<com.pijava.ai.message.Message.AssistantMessage>
        bugReportHook = message -> { };

    SessionEventRouter(ChatPanel chatPanel, PendingQueueView pendingQueue,
                       CacheNoticeRenderer cacheNotices) {
        this.chatPanel = chatPanel;
        this.pendingQueue = pendingQueue;
        this.cacheNotices = cacheNotices;
    }

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
    void onSessionEvent(AgentSessionEvent event) {
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
                    cacheNotices.appendUsageNotice(usage);
                    cacheNotices.markLiveNotice(usage.id());
                }
            }
            // 批 13（T11 即时臂，pi :4185-4191＋:3593 挂点对位——message_end 先于
            // 落盘，tracker 只见过已投递条目 ⇒「entries 未含 message」天然成立）。
            case AgentSessionEvent.MessageEnd end -> {
                if (end.message() instanceof com.pijava.ai.message.Message.AssistantMessage am) {
                    if (am.stopReason() != com.pijava.ai.message.StopReason.ABORTED
                            && am.stopReason() != com.pijava.ai.message.StopReason.ERROR) {
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
    void showWarning(String message) {
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
    void showBranchSummaryIndicator() {
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

    /** 往聊天区追加一条错误（pi {@code showError}，{@code :4273-4277}：红字 + {@code Error: } 前缀）。 */
    void showError(String message) {
        chatPanel.append(new ChatMessage.Error("Error: " + message));
    }
}
