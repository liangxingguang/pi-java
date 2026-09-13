package com.pijava.agent.harness;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.telemetry.TelemetrySpan;

/**
 * Internal per-lane state for {@link AgentHarness} — pi {@code AgentState} 的 Java 版
 * （{@code docs/31 §3.1}）。
 *
 * <p>Only AgentHarness and its collaborators create and mutate this.</p>
 *
 * <p><b>运行态由 {@link #activeRun} 表达</b>（{@code docs/31 §3.2}）：非空即为正在运行。
 * 原先的 {@code RunPhase} 枚举与「先记账后落盘」的 {@code pendingWrites} 队列都已删除
 * —— entry 一旦产生就直接进 {@link #transcript}，没有中间态。</p>
 *
 * <p><b>一个 harness 恰好一条车道</b>（{@code docs/31 §4.3}）。运行时多车道容器
 * （{@code LaneRegistry} / {@code LaneHandle} / {@code LaneConfig}）已删除，每个
 * {@code AgentSession} 持有自己的 harness —— pi 的 {@code AgentState} 也是单状态的，
 * 「多分支」归会话层（存储层 lane 保留，那正是 pi 的分支模型）。于是
 * 原先挂在 {@code HarnessState} 上的可变配置并回了这里，与 pi 的字段布局一致。</p>
 */
public final class LaneState {

    /** Lane identifier. */
    String laneName = "default";

    /**
     * 车道名 —— 恒为 {@link AgentHarness#DEFAULT_LANE}（{@code docs/31 §4.3}）。
     *
     * <p>公开访问器：{@code HookSystem} 在 {@code com.pijava.agent.hook} 包，
     * 需要它来辨认「这条 hook 错误属于本车道吗」。</p>
     */
    public String laneName() {
        return laneName;
    }

    /** The lane's entry log — 持久真源，{@link PiLaneSink} 与 run 起手直接追加。 */
    final List<Entry> transcript = new ArrayList<>();

    /**
     * 工作副本 —— 送给 provider 的那份消息列表（pi {@code AgentState.messages}，
     * {@code docs/31 §4.2}）。
     *
     * <p><b>两者分工</b>：{@link #transcript} 是持久真源，工作副本是它的投影。副本由
     * **事件**维护（{@link PiLaneSink} 在 {@code message_end} 上追加，对齐 pi
     * {@code agent.ts:556} 的 {@code processEvents}），只在日志被**整体替换**时重建
     * （resume 播种、压缩、搬迁）。此前每次请求都从 entry 日志重走一遍
     * {@code pathToLeaf} —— 那是本字段存在的理由。</p>
     *
     * <p>与 {@link #transcript} 的一处有意差异：起手的用户 prompt 因「日志里已有」被
     * {@link PiLaneSink} 抑制落盘，但**照样进副本** —— pi 的用户消息也是经
     * {@code message_end} 进 {@code state.messages} 的。</p>
     */
    final List<Message> messages = new ArrayList<>();

    /** Current run identifier. */
    String runId;

    /** Current assistant message partial snapshot (from last event). */
    AssistantMessage partial;

    /** Summary of the newest own entry (for stopReason checks). */
    NewestOwn newestOwn;

    /** Internal operation records for debugging and audit. */
    public final List<LaneRecord> records = new ArrayList<>();

    /**
     * 当前运行；{@code null} 表示车道空闲（{@code docs/31 §3.2}）。
     *
     * <p>它是唯一的「是否在跑」判据 —— 取代了 {@code RunPhase} 三态枚举。</p>
     */
    ActiveRun activeRun;

    /** Open {@code harness.run} telemetry span for the current run (observability). */
    TelemetrySpan runSpan;

    /** Run start wall-clock for OperationFinished.durationMs / harness.run duration. */
    long runStartNanos;

    /**
     * 已写进 {@link #transcript} 的思考等级标签；{@code null} 表示尚未记录。
     *
     * <p>pi 只在等级**真的变了**时才追加 entry（{@code agent-session.ts:1813-1829} 的
     * {@code isChanging} 守卫），所以需要一个「上次记的是什么」的判据，否则每次运行都会
     * 重复追加一条（{@code docs/31 §4.1}）。</p>
     */
    String recordedThinking;

    /**
     * 溢出恢复一次性闩锁（pi {@code _overflowRecoveryAttempted}，
     * {@code agent-session.ts:2194-2218}；package 3c，{@code docs/31 §8.21}）。
     *
     * <p>溢出/截断的恢复预算是<b>一次</b>：第一次命中设闩并 compact-and-retry，
     * 闩已立 ⇒ 宣告失败（固定文案的 compaction_end）不再压。重置点与 pi 一致、
     * 都在**会话层事件**上：新用户消息的 {@code message_start}（pi :643）、
     * 助手 {@code message_end} 且 stopReason ∉ {error, length}（pi :694-696）。
     * 它是 pi 的 session 级字段（跨 run 存活），故住车道而非 ActiveRun。</p>
     */
    boolean overflowRecoveryAttempted;

    // ═══════════════════════════════════════════════════════════
    // 配置（pi AgentState 的字段：model / thinkingLevel / systemPrompt / tools）
    // ═══════════════════════════════════════════════════════════

    /**
     * 下一次运行使用的配置（{@code docs/31 §4.1} 表格里仍挂在「harness」那一行）。
     *
     * <p>它们此前住在 {@code HarnessState} —— 一个「一个 harness 有多条车道」时代的
     * 独立可变配置对象。容器删除后每个 harness 只有一条车道，而 pi 的
     * {@code AgentState} 本来就把这些字段和消息放在一起，于是并回这里：字段仍是唯一真源，
     * {@link AgentHarness} 的 setter 直接改它，entry 只是它的审计副本。</p>
     */
    ModelId<?> model;
    ModelThinkingLevel thinkingLevel;
    String systemPrompt;
    Set<AgentTool<?, ?>> activeTools;
    CompactionSettings compactionSettings;
    /** steer / followUp 队列的排空模式（pi 在 Agent 上是纯内存的，不入日志）。 */
    QueueMode steeringMode;
    QueueMode followUpMode;
    ToolExecution toolExecution;

    // Phase 3: scheduling queues (steer / followUp / nextRun)
    /** Steering queue — injected into the current run's next assistant round. */
    final ArrayDeque<LaneInfo.QueuedItem> steerQueue = new ArrayDeque<>();

    /** Follow-up queue — processed when the current run finishes. */
    final ArrayDeque<LaneInfo.QueuedItem> followUpQueue = new ArrayDeque<>();

    /** Next-run queue — starts a new run when the lane is idle. */
    final ArrayDeque<LaneInfo.QueuedItem> nextRunQueue = new ArrayDeque<>();

    /** Monotonic sequence number shared by all three queues. */
    long queueSeq;

    // ── Helpers ──────────────────────────────────────────────

    /** Whether a run is in flight on this lane (pi {@code this.activeRun !== undefined}). */
    boolean isRunning() {
        return activeRun != null;
    }

    /**
     * 当前运行的取消信号，空闲时为 {@code null}（pi {@code this.activeRun?.abortController.signal}）。
     *
     * <p>空闲时返回 {@code null} 是**有意的**：pi 的 {@code abort()} 在空闲时也无事可做。
     * 原先在恢复时会预先装一个信号「让恢复后的车道可中止」，那是三态枚举时代的产物
     * —— 空闲车道本就没有可中止的东西。</p>
     */
    AbortSignal abortSignal() {
        return activeRun == null ? null : activeRun.signal();
    }

    /** Snapshot the three queues for {@link LaneInfo.Queues}. */
    LaneInfo.Queues queueSnapshot() {
        return new LaneInfo.Queues(
            List.copyOf(steerQueue),
            List.copyOf(followUpQueue),
            List.copyOf(nextRunQueue));
    }

    /** Derive the next sequence number. */
    long nextSeq() {
        return transcript.size();
    }

    /** 应用一次 {@code prepare_next_turn} 的配置更新（{@code null} 表示不改该项）。 */
    void applyTurn(ModelId<?> newModel, String thinkingLevelLabel) {
        if (newModel != null) {
            model = newModel;
        }
        if (thinkingLevelLabel != null) {
            thinkingLevel = "off".equals(thinkingLevelLabel)
                ? ModelThinkingLevel.off()
                : ModelThinkingLevel.of(parseThinkingLabel(thinkingLevelLabel));
        }
    }

    /** 标签 → 思考等级。包内可见：{@link PiLaneEngine} 把它用于 {@code prepareNextTurn}。 */
    static com.pijava.ai.thinking.ThinkingLevel parseThinkingLabel(String label) {
        return switch (label) {
            case "minimal" -> new com.pijava.ai.thinking.ThinkingLevel.Minimal();
            case "low" -> new com.pijava.ai.thinking.ThinkingLevel.Low();
            case "medium" -> new com.pijava.ai.thinking.ThinkingLevel.Medium();
            case "high" -> new com.pijava.ai.thinking.ThinkingLevel.High();
            case "xhigh" -> new com.pijava.ai.thinking.ThinkingLevel.XHigh();
            default -> throw new IllegalArgumentException("Unknown thinking level: " + label);
        };
    }

    /** The most recent entry, or null. */
    Entry lastEntry() {
        return transcript.isEmpty() ? null : transcript.get(transcript.size() - 1);
    }

    // ═══════════════════════════════════════════════════════════
    // NewestOwn — summary of the latest own entry
    // ═══════════════════════════════════════════════════════════

    /**
     * Summary of the newest entry produced by the agent itself
     * (not by the user or external systems).
     *
     * <p>Used to derive the run outcome ({@link HarnessUtils#determineOutcome}).</p>
     */
    record NewestOwn(
        String entryId,
        String entryType,    // "message" | "thinking_level_change" | ...
        String role,         // "user" | "assistant" | "tool" (only for message type)
        String stopReason    // "stop" | "tool_use" | "error" | "length" | null
    ) {}
}
