package com.pijava.coding.agent.extension.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.pijava.agent.context.ContextUsageEstimator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.hook.BeforeSettleContext;
import com.pijava.agent.hook.ShouldStopAfterTurnContext;
import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionError;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentBeforeSettleEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.TurnEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionResults.BoundaryContextPreview;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBoundaryDraft;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.AGENT_BEFORE_SETTLE;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.TURN_END;

/**
 * boundary 派发子系统（docs/15 §3.5）—— pi {@code emitBoundary}
 * （runner.ts:1029-1078）、{@code _dispatchTurnEndBoundary}（agent-session.ts:846-896）、
 * {@code _runBeforeSettleBoundary}（:1893-1919）、{@code _buildBoundaryContext}（:999-1022）、
 * {@code _commitBoundaryDrafts}（:1024-1028）的合订对位。
 *
 * <p>装配期恒装两适配器（裁决②）：{@code should_stop_after_turn} 挂 {@link #turnEnd}、
 * {@code before_settle} 挂 {@link #beforeSettle}；无订阅者弃权（引擎回落既有行为，
 * agent-core 独立使用逐比特不变）。</p>
 *
 * <p>{@code outcome} 跟踪同 pi（:850-851 在 hasHandlers 门<b>之前</b>——turn_end 无
 * 订阅者也照记，before_settle 的载荷要用）。</p>
 */
public final class BoundaryBridge {

    /** 一次 boundary 派发的结果（pi {@code BoundaryDispatchResult} 的本批用面）。 */
    record Dispatch(List<SessionBoundaryDraft> entries, boolean shouldContinue) {}

    /** turnIndex 共享计数（pi {@code _turnIndex} 同字段）：宿主桥递增、boundary 读。 */
    public static final class TurnIndex {
        int value;
    }

    private static final String NO_ENTRY_ID =
        "turn_end could not resolve the persisted assistant entry ID";

    private final AgentHarness harness;
    private final String laneName;
    private final ExtensionEventBus bus;
    private final ExtensionContext ctx;
    private final Consumer<Entry> commitSink;
    private final BooleanSupplier abortProbe;
    private final TurnIndex turnIndex;
    /** 批 26（docs/37 T2）：pi :1903 对位——草稿提交后 flush 延迟 custom 消息。 */
    private final Runnable pendingCustomFlush;
    private String outcome = "completed";

    BoundaryBridge(AgentHarness harness, String laneName, ExtensionEventBus bus,
                   ExtensionContext ctx, Consumer<Entry> commitSink,
                   BooleanSupplier abortProbe, TurnIndex turnIndex) {
        this(harness, laneName, bus, ctx, commitSink, abortProbe, turnIndex, () -> { });
    }

    BoundaryBridge(AgentHarness harness, String laneName, ExtensionEventBus bus,
                   ExtensionContext ctx, Consumer<Entry> commitSink,
                   BooleanSupplier abortProbe, TurnIndex turnIndex,
                   Runnable pendingCustomFlush) {
        this.harness = harness;
        this.laneName = laneName;
        this.bus = bus;
        this.ctx = ctx;
        this.commitSink = commitSink;
        this.abortProbe = abortProbe;
        this.turnIndex = turnIndex;
        this.pendingCustomFlush = pendingCustomFlush;
    }

    // ═══════════════════════════════════════════════════════════
    // turn_end 适配器（pi _dispatchTurnEndBoundary :846-882 + :884-896）
    // ═══════════════════════════════════════════════════════════

    /**
     * {@code should_stop_after_turn} 钩：先记 outcome（恒），再无订阅者弃权；
     * entryId 解析失败 ⇒ "{@code <boundary>}" 上报＋不派发；continue 经校验 ⇒
     * {@link PiLoop.TurnDecision#CONTINUE}。
     */
    PiLoop.TurnDecision turnEnd(ShouldStopAfterTurnContext c) {
        // pi :850-851：outcome 在 hasHandlers 门之前恒记。
        var stop = c.assistantMessage().stopReason();
        outcome = stop == StopReason.ABORTED ? "aborted"
            : stop == StopReason.ERROR ? "error" : "completed";
        if (!bus.hasHandlers(TURN_END)) {
            return null;
        }
        var ids = messageEntryIds();
        var messageEntryId = ids.get(c.assistantMessage());
        if (messageEntryId == null) {
            bus.emitError(new ExtensionError("<boundary>", "turn_end", NO_ENTRY_ID, null));
            return null;
        }
        var toolResultEntryIds = c.toolResults().stream()
            .map(ids::get).filter(Objects::nonNull).toList();
        var result = dispatchTurnEnd(c.assistantMessage(), c.toolResults(),
            messageEntryId, toolResultEntryIds);
        commit(result.entries());
        if (!result.shouldContinue()) {
            return null;
        }
        // pi :879-881：continue 但上下文不可续 ⇒ 上报＋照常调度。
        if (!buildContext(List.of(), "turn_end").canContinue()) {
            bus.emitError(new ExtensionError("<boundary>", "turn_end",
                "turn_end requested continuation without runnable model context", null));
            return null;
        }
        return PiLoop.TurnDecision.CONTINUE;
    }

    // ═══════════════════════════════════════════════════════════
    // agent_before_settle 适配器（pi _runBeforeSettleBoundary :1893-1919）
    // ═══════════════════════════════════════════════════════════

    /**
     * {@code before_settle} 钩：无订阅者弃权（引擎按无桥行为收口——pi 此刻队列必空，
     * 同值）；否则派发 ⇒ 提交草稿 ⇒ {@code continue || hasQueuedMessages}。
     */
    Boolean beforeSettle(BeforeSettleContext c) {
        if (!bus.hasHandlers(AGENT_BEFORE_SETTLE)) {
            return null;
        }
        var result = dispatchBeforeSettle();
        commit(result.entries());
        // 批 26（docs/37 T2）：pi :1903——commit 后 flush 延迟 custom 消息
        //（批 26 前 java 无 pendingCustom 面〔D21〕，本批落地）。
        pendingCustomFlush.run();
        if (abortProbe.getAsBoolean()) {
            return false; // pi _abortDuringBeforeSettle
        }
        boolean shouldContinue = result.shouldContinue() || hasQueuedMessages();
        if (shouldContinue && !buildContext(List.of(), "agent_before_settle").canContinue()) {
            if (result.shouldContinue()) {
                bus.emitError(new ExtensionError("<boundary>", "agent_before_settle",
                    "agent_before_settle requested continuation without runnable model context",
                    null));
            }
            return false;
        }
        return shouldContinue;
    }

    // ═══════════════════════════════════════════════════════════
    // emitBoundary（pi runner.ts:1029-1078 逐字）
    // ═══════════════════════════════════════════════════════════

    private Dispatch dispatchTurnEnd(Message.AssistantMessage message,
            List<Message.ToolResultMessage> toolResults, String messageEntryId,
            List<String> toolResultEntryIds) {
        var entries = new ArrayList<SessionBoundaryDraft>();
        boolean shouldContinue = false;
        var context = buildContext(entries, "turn_end");
        for (var h : bus.snapshot(TURN_END)) {
            var event = new TurnEndEvent(turnIndex.value, message, toolResults,
                messageEntryId, toolResultEntryIds, List.copyOf(entries), shouldContinue,
                context, outcome);
            try {
                var r = h.handle(event, ctx);
                if (r != null) {
                    if (r.entries() != null) {
                        entries.clear();
                        entries.addAll(r.entries());
                    }
                    if (r.shouldContinue() != null) {
                        shouldContinue = r.shouldContinue();
                    }
                }
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
            // pi :1058-1068：每个处理器之后都用最新 entries 重建预览；失败 ⇒
            // "Invalid boundary entries: …" 上报＋空草稿返回。
            try {
                context = buildContext(entries, "turn_end");
            } catch (RuntimeException e) {
                bus.emitError(h.error("Invalid boundary entries: "
                    + (e.getMessage() == null ? String.valueOf(e) : e.getMessage())));
                return new Dispatch(List.of(), false);
            }
        }
        return new Dispatch(List.copyOf(entries), shouldContinue);
    }

    private Dispatch dispatchBeforeSettle() {
        var entries = new ArrayList<SessionBoundaryDraft>();
        boolean shouldContinue = false;
        var context = buildContext(entries, "agent_before_settle");
        for (var h : bus.snapshot(AGENT_BEFORE_SETTLE)) {
            var event = new AgentBeforeSettleEvent(List.copyOf(entries), shouldContinue,
                context, outcome);
            try {
                var r = h.handle(event, ctx);
                if (r != null) {
                    if (r.entries() != null) {
                        entries.clear();
                        entries.addAll(r.entries());
                    }
                    if (r.shouldContinue() != null) {
                        shouldContinue = r.shouldContinue();
                    }
                }
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
            try {
                context = buildContext(entries, "agent_before_settle");
            } catch (RuntimeException e) {
                bus.emitError(h.error("Invalid boundary entries: "
                    + (e.getMessage() == null ? String.valueOf(e) : e.getMessage())));
                return new Dispatch(List.of(), false);
            }
        }
        return new Dispatch(List.copyOf(entries), shouldContinue);
    }

    // ═══════════════════════════════════════════════════════════
    // 预览（pi _buildBoundaryContext :999-1022）与提交（:944-987, :1024-1028）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code _buildBoundaryContext}：branch 尾部追加草稿（投影副本，不动真 transcript）
     * ⇒ 投影 ⇒ canContinue 逐字（末条 role＋排队判定，两事件形分支照 pi）。
     */
    BoundaryContextPreview buildContext(List<SessionBoundaryDraft> drafts, String kind) {
        var snapshot = harness.snapshot(laneName);
        var branch = ContextEntries.pathToLeaf(snapshot.transcript(), snapshot.leafId());
        var preview = new ArrayList<Entry>(branch);
        for (var draft : drafts) {
            preview.add(draftToEntry(draft, branch));
        }
        var projected = ContextEntries.projectEntries(preview);
        var contextMessages = ContextEntries.toMessages(preview);
        // D15：java 无 pi 的 AgentMessage→LLM 二次转换缝，llmMessages 与 contextMessages 同源。
        var llmMessages = contextMessages;
        var pending = pendingMessages();
        Message last = llmMessages.isEmpty() ? null : llmMessages.getLast();
        boolean contextCanContinue =
            llmMessages.stream().anyMatch(m -> !(m instanceof Message.SystemMessage))
                && !(last instanceof Message.AssistantMessage);
        boolean hasQueued = hasQueuedMessages();
        boolean canContinue = contextCanContinue
            || ("turn_end".equals(kind)
                ? hasQueued
                : last instanceof Message.AssistantMessage && hasQueued);
        return new BoundaryContextPreview(projected, contextMessages, llmMessages, pending,
            canContinue);
    }

    /** pi {@code _commitBoundaryDrafts}＋{@code _applyBoundaryDrafts}：逐草稿落 entry（含 entry_appended＋落盘，由 commitSink 承担）。 */
    void commit(List<SessionBoundaryDraft> drafts) {
        var branch = harness.snapshot(laneName).transcript();
        for (var draft : drafts) {
            commitSink.accept(draftToEntry(draft, branch));
        }
    }

    /** 草稿 → entry（pi {@code _applyBoundaryDrafts} 的 switch 四支）。 */
    private Entry draftToEntry(SessionBoundaryDraft draft, List<Entry> branch) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        return switch (draft) {
            case SessionBoundaryDraft.Custom c ->
                new Entry.Custom(id, 0, null, now, c.customType(),
                    c.data() == null ? Map.of() : c.data());
            case SessionBoundaryDraft.CustomMessage c ->
                new Entry.CustomMessage(id, 0, null, now, c.customType(),
                    c.content() == null
                        ? new com.pijava.agent.entry.CustomMessageContent.Text("") : c.content(),
                    c.display(), c.details());
            case SessionBoundaryDraft.ContextEdit c ->
                new Entry.ContextEdit(id, 0, null, now, c.targetId(), c.replacement());
            case SessionBoundaryDraft.Compaction c -> {
                // pi :966-976：tokensBefore 走投影估算（CompactionExecutor.contextTokens
                // 同源）；firstKeptEntryId 为 null ⇒ 自留压缩（pi 注释逐字）。
                int tokensBefore = (int) ContextUsageEstimator.estimateProjectedContextTokens(
                    ContextEntries.projectEntries(branch), branch).tokens();
                yield new Entry.Compaction(id, 0, null, now, c.summary(), c.firstKeptEntryId(),
                    c.firstKeptEntryId() == null ? List.of()
                        : keptMessagesFrom(branch, c.firstKeptEntryId()),
                    tokensBefore, c.details(),
                    c.usage(), true);
            }
        };
    }

    /** pi 的 {@code _entryIdsByMessage}（:934-941）：投影的消息引用 → 条目 id（按需构建）。 */
    private Map<Message, String> messageEntryIds() {
        var snapshot = harness.snapshot(laneName);
        var branch = ContextEntries.pathToLeaf(snapshot.transcript(), snapshot.leafId());
        var ids = new IdentityHashMap<Message, String>();
        for (var pe : ContextEntries.projectEntries(branch)) {
            for (var message : pe.messages()) {
                ids.putIfAbsent(message, pe.source().id());
            }
        }
        return ids;
    }

    /** pi {@code _getPendingBoundaryMessages}：队列文本重建 UserMessage（D21 窄差）。 */
    private List<Message> pendingMessages() {
        var pending = new ArrayList<Message>();
        for (var text : harness.queuedSteering(laneName)) {
            pending.add(new Message.UserMessage(List.of(new ContentBlock.TextContent(text))));
        }
        for (var text : harness.queuedFollowUp(laneName)) {
            pending.add(new Message.UserMessage(List.of(new ContentBlock.TextContent(text))));
        }
        return pending;
    }

    private boolean hasQueuedMessages() {
        return !harness.queuedSteering(laneName).isEmpty()
            || !harness.queuedFollowUp(laneName).isEmpty();
    }

    /** {@code Entry.Compaction.retainedTail}：firstKeptId 起的 Message 载荷序列。 */
    private static List<Message> keptMessagesFrom(List<Entry> transcript, String firstKeptId) {
        List<Message> kept = new ArrayList<>();
        boolean seen = false;
        for (var entry : transcript) {
            if (entry.id().equals(firstKeptId)) {
                seen = true;
            }
            if (seen && entry instanceof Entry.Message msg) {
                kept.add(msg.message());
            }
        }
        return kept;
    }
}
