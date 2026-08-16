package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.ai.AbortSignal;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.message.AssistantMessage;

/**
 * Internal per-lane state for {@link AgentHarness}.
 *
 * <p>Only AgentHarness and its collaborators create and mutate this.
 * Phase 2a supports a single lane; multi-lane in Phase 2c.</p>
 *
 * <p>Aligned with pi's {@code LaneState}. Key design: messages are NOT stored
 * directly — they are built from {@link #transcript} entries on each LLM request
 * by {@code ActionExecutor}.</p>
 */
public final class LaneState {

    /** Lane identifier. */
    String laneName = "default";

    /** Current run phase. */
    RunPhase phase = RunPhase.IDLE;

    /** The full transcript of entries (messages are built from these). */
    final List<Entry> transcript = new ArrayList<>();

    /** Current run identifier. */
    String runId;

    /** Monotonic step counter within the current run. */
    int stepIndex;

    /** Current assistant message partial snapshot (from last event). */
    AssistantMessage partial;

    /** Summary of the newest own entry (for stopReason checks). */
    NewestOwn newestOwn;

    /** Entries provisioned but not yet persisted. */
    final List<Entry> pendingWrites = new ArrayList<>();

    /** Internal operation records for debugging and audit (Phase 2a). */
    public final List<LaneRecord> records = new ArrayList<>();

    /** Pending tool calls to execute (populated after tool_use stopReason). Phase 2b. */
    final List<Action.ExecuteTool> pendingToolCalls = new ArrayList<>();

    /** Abort signal for the current run. Phase 2b. */
    AbortSignal abortSignal;

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

    /** Snapshot the three queues for {@link LaneInfo.Queues}. */
    LaneInfo.Queues queueSnapshot() {
        return new LaneInfo.Queues(
            List.copyOf(steerQueue),
            List.copyOf(followUpQueue),
            List.copyOf(nextRunQueue));
    }

    // ── Helpers ──────────────────────────────────────────────

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
     * <p>Aligned with pi {@code LaneState.newestOwn}.
     * Used in checkpoint phase to determine the outcome of
     * {@code TryFinishRun}.</p>
     */
    record NewestOwn(
        String entryId,
        String entryType,    // "message" | "thinking_level_change" | ...
        String role,         // "user" | "assistant" | "tool" (only for message type)
        String stopReason    // "stop" | "tool_use" | "error" | "length" | null
    ) {}
}
