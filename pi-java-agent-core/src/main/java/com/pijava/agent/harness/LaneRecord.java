package com.pijava.agent.harness;

import java.time.Instant;
import java.util.Map;

/**
 * A lane-level internal operation record, used for debugging and audit.
 *
 * <p>Unlike {@link Entry}, lane records are not shown to the user.
 * They track internal harness operations: operation start/finish,
 * tool execution, queue management, and usage.</p>
 */
public sealed interface LaneRecord {

    /** Monotonic sequence number. */
    long seq();

    /** When this record was created. */
    Instant timestamp();

    /** An operation (run / resume) started. */
    record OperationStarted(
        long seq, Instant timestamp,
        String runId,
        String intent
    ) implements LaneRecord {}

    /** An abort was requested. */
    record AbortRequested(
        long seq, Instant timestamp,
        String reason
    ) implements LaneRecord {}

    /** An operation finished. */
    record OperationFinished(
        long seq, Instant timestamp,
        String runId,
        String status
    ) implements LaneRecord {}

    /** A single LLM call attempt. */
    record StepAttempt(
        long seq, Instant timestamp,
        int stepIndex,
        long inputTokens,
        long outputTokens
    ) implements LaneRecord {}

    /** A tool started executing. */
    record ToolStarted(
        long seq, Instant timestamp,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
    ) implements LaneRecord {
        public ToolStarted {
            arguments = Map.copyOf(arguments);
        }
    }

    /** An item was enqueued. */
    record QueueEnqueued(
        long seq, Instant timestamp,
        String queueType,
        String content
    ) implements LaneRecord {}

    /** A queue was cancelled. */
    record QueueCancelled(
        long seq, Instant timestamp,
        String queueType
    ) implements LaneRecord {}

    /** A write operation was deferred. */
    record WriteDeferred(
        long seq, Instant timestamp,
        String entryId
    ) implements LaneRecord {}

    /** Token usage was recorded. */
    record UsageRecord(
        long seq, Instant timestamp,
        long inputTokens,
        long outputTokens,
        String modelId
    ) implements LaneRecord {}
}
