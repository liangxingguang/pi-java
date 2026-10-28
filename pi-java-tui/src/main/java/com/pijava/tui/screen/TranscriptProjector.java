package com.pijava.tui.screen;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.MetaKind;

import java.util.List;

/**
 * 批 29（E2 结构债拆分——为守 FileLength≤500 闸门自 ChatScreen 追加移出，
 * 行为零变化）：会话/流式事件 → 转录气泡的投影核心——{@code onEntry} 去重、
 * {@code onStreamEvent} 打字机草稿、导航后整表重建、run 收尾分隔线、乐观
 * 用户气泡、welcome 卡。跨线程可见性注释随字段原样迁移（台账 B45／spotbugs
 * 钉）。{@code outputPad} 经供应器现读 ChatScreen 字段（原 this.outputPad
 * 直读同义）；cache 通知收口委托 {@link CacheNoticeRenderer}。
 */
final class TranscriptProjector {

    private final ChatPanel chatPanel;
    private final java.util.function.IntSupplier outputPad;
    private final CacheNoticeRenderer cacheNotices;
    private final StringBuilder assistantDraft = new StringBuilder();
    private final StringBuilder thinkingDraft = new StringBuilder();
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

    TranscriptProjector(ChatPanel chatPanel, java.util.function.IntSupplier outputPad,
                        CacheNoticeRenderer cacheNotices) {
        this.chatPanel = chatPanel;
        this.outputPad = outputPad;
        this.cacheNotices = cacheNotices;
    }

    /** Receive a complete transcript entry (dedupes streamed assistant text). */
    void onEntry(Entry entry) {
        // 批 13（T11）：先 feed tracker 再渲染——assistant 气泡被 streaming 去重
        // 跳过也要 feed（条目客观存在；pi scan 同样遍历全量）。miss 通知在条目
        // 自身气泡<b>之后</b>注入（pi :4062-4065 的序）。
        var trackedMiss = cacheNotices.feed(entry);
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
            if (!cacheNotices.liveNoticeKnown(usage.id())) {
                cacheNotices.appendUsageNotice(usage);
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
        if (trackedMiss.isPresent() && cacheNotices.noticesEnabled()
                && entry instanceof Entry.Message messageEntry
                && messageEntry.message() instanceof com.pijava.ai.message.Message.AssistantMessage am
                && !cacheNotices.isLastMissNotified(am)
                && am.stopReason() != com.pijava.ai.message.StopReason.ABORTED
                && am.stopReason() != com.pijava.ai.message.StopReason.ERROR) {
            cacheNotices.appendCacheMissNotice(trackedMiss.get());
        }
    }

    /** Incremental stream events → draft inside the viewport (typewriter). */
    void onStreamEvent(StreamEvent event) {
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
    void showWelcome(String cardText) {
        chatPanel.append(new ChatMessage.System(cardText, MetaKind.GENERIC));
    }

    /** Reset per-run tool accounting (called before each prompt submission). */
    void resetRunTracking() {
        runToolCalls.set(0);
    }

    /**
     * Appends the inter-turn separator (Codex-CLI style) once the transcript
     * entries have been committed, but only for runs that used tools.
     * Elapsed time is measured from submission to status completion.
     */
    void finishRun(long elapsedNanos) {
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

    /**
     * 导航后整表重建（docs/18 §3.11；pi {@code chatContainer.clear()}＋
     * {@code renderInitialMessages()}，interactive-mode.ts:5671-5672）：
     * 清面板＋草稿，按新叶 {@code pathToLeaf} 逐条 {@link ChatMessage#from} 重放。
     *
     * @param entries 会话全量 entries（{@code AgentSession.accumulatedEntries()}）
     * @param leafId  导航后的当前叶（null ⇒ 空转录）
     */
    void rebuildTranscript(java.util.List<Entry> entries, String leafId) {
        chatPanel.clear();
        assistantDraft.setLength(0);
        thinkingDraft.setLength(0);
        chatPanel.setDraft(null);
        pendingUserText = null;
        assistantStreamed = false;
        thinkingRendered = false;
        // 批 13（T10/T11）：重建即新一轮增量流——去重集与 miss 跟踪器归零。
        cacheNotices.reset();
        if (leafId != null) {
            for (var e : com.pijava.agent.session.ContextEntries.pathToLeaf(entries, leafId)) {
                // 批 13（T11 重建臂，pi :3999-4065 对位）：逐条 feed，assistant
                // 渲染后注入其 miss（aborted/error 不注入，即时臂同实例去重）。
                var trackedMiss = cacheNotices.feed(e);
                // 批 13（T10）：usage 条目同走门控收口。
                if (e instanceof Entry.Usage usage) {
                    cacheNotices.appendUsageNotice(usage);
                    continue;
                }
                var bubble = ChatMessage.from(e, outputPad.getAsInt());
                if (bubble != null) {
                    chatPanel.append(bubble);
                }
                if (trackedMiss.isPresent() && cacheNotices.noticesEnabled()
                        && e instanceof Entry.Message messageEntry
                        && messageEntry.message()
                                instanceof com.pijava.ai.message.Message.AssistantMessage am
                        && !cacheNotices.isLastMissNotified(am)
                        && am.stopReason() != com.pijava.ai.message.StopReason.ABORTED
                        && am.stopReason() != com.pijava.ai.message.StopReason.ERROR) {
                    cacheNotices.appendCacheMissNotice(trackedMiss.get());
                }
            }
        }
        chatPanel.scrollToBottom();
    }

    /** Show the user's message immediately on submit (optimistic bubble). */
    void appendUserText(String text) {
        pendingUserText = text;
        // 批 12 T6 注记：乐观气泡<b>不</b>烘焙 pad（设计消费面＝from(entry) 链
        // 三处；提交后的条目经 pendingUserText 去重本就不再渲染，无跳动面）。
        chatPanel.append(new ChatMessage.User(text));
    }
}
