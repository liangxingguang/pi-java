package com.pijava.agent.record;

import java.time.Instant;
import java.util.Map;

/**
 * A lane-level internal operation record, used for debugging and audit.
 *
 * <p>Unlike {@link com.pijava.agent.entry.Entry}, lane records are not shown
 * to the user. They track internal harness operations: operation start/finish,
 * tool execution, queue management, and usage.</p>
 *
 * <p>Java sealed types require all permitted subtypes at compile time;
 * all 9 are declared here but only 5 are constructed in Phase 2a.</p>
 */
public sealed interface LaneRecord {
    RecordHeader header();

    // ── Helpers ──────────────────────────────────────────────

    static RecordHeader newHeader(long seq) {
        return new RecordHeader(seq, Instant.now());
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 2a subtypes
    // ═══════════════════════════════════════════════════════════

    /** An operation (run / resume) started. */
    record OperationStarted(
        RecordHeader header,
        String runId,
        String intent
    ) implements LaneRecord {}

    /** An abort was requested. */
    record AbortRequested(
        RecordHeader header,
        String reason
    ) implements LaneRecord {}

    /** An operation finished. */
    record OperationFinished(
        RecordHeader header,
        String runId,
        String status       // "completed" | "aborted" | "error"
    ) implements LaneRecord {}

    /** A single LLM call attempt. */
    record StepAttempt(
        RecordHeader header,
        int stepIndex,
        long inputTokens,
        long outputTokens
    ) implements LaneRecord {}

    /** Token usage was recorded. */
    record UsageRecord(
        RecordHeader header,
        long inputTokens,
        long outputTokens,
        String modelId
    ) implements LaneRecord {}

    // ═══════════════════════════════════════════════════════════
    // Phase 2b subtypes
    // ═══════════════════════════════════════════════════════════

    /** A tool started executing (Phase 2b). */
    record ToolStarted(
        RecordHeader header,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
    ) implements LaneRecord {
        public ToolStarted {
            arguments = Map.copyOf(arguments);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 2c subtypes
    // ═══════════════════════════════════════════════════════════

    /** An item was enqueued (Phase 2c). */
    record QueueEnqueued(
        RecordHeader header,
        String queueType,    // "steer" | "followUp" | "nextRun"
        String content
    ) implements LaneRecord {}

    /** A queue was cancelled (Phase 2c). */
    record QueueCancelled(
        RecordHeader header,
        String queueType
    ) implements LaneRecord {}

    /** A write operation was deferred (Phase 2c). */
    record WriteDeferred(
        RecordHeader header,
        String entryId
    ) implements LaneRecord {}

    /** A lifecycle hook threw an exception (Phase 2c). Non-fatal. */
    record HookError(
        RecordHeader header,
        String hookName,
        String message
    ) implements LaneRecord {}
}
