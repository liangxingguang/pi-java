package com.pijava.agent.harness;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.AssistantMessage;
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
 */
public final class LaneState {

    /** Lane identifier. */
    String laneName = "default";

    /** The lane's entry log — 持久真源，{@link PiLaneSink} 与 run 起手直接追加。 */
    final List<Entry> transcript = new ArrayList<>();

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

    /** Pending update from prepare_next_turn hooks; consumed by the next turn, cleared at run end. */
    com.pijava.agent.hook.TurnUpdate pendingTurnUpdate;

    /**
     * 已写进 {@link #transcript} 的思考等级标签；{@code null} 表示尚未记录。
     *
     * <p>pi 只在等级**真的变了**时才追加 entry（{@code agent-session.ts:1813-1829} 的
     * {@code isChanging} 守卫），所以需要一个「上次记的是什么」的判据，否则每次运行都会
     * 重复追加一条（{@code docs/31 §4.1}）。</p>
     */
    String recordedThinking;

    // Phase 2c: multi-lane fields
    /** Parent leaf ID for branching; null for the default lane. */
    String parentLeafId;

    /** Lane-level tool override; null means inherit from harness. */
    Set<AgentTool<?, ?>> activeTools;

    /** Lane-level system prompt override; null means inherit from harness. */
    String systemPrompt;

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
