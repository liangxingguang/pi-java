package com.pijava.agent.record;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pijava.ai.Usage;

/**
 * A lane-level internal operation record, used for debugging, audit and
 * recovery. Unlike {@link com.pijava.agent.entry.Entry}, lane records are not
 * shown to the user.
 *
 * <p>Identity fields ({@code id}/{@code seq}/{@code lane}/{@code timestamp})
 * are stored flat on every subtype, aligned with pi's {@code LaneRecord}
 * union. The storage assigns {@code seq}/{@code timestamp} on commit.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LaneRecord.OperationStarted.class, name = "operation_started"),
    @JsonSubTypes.Type(value = LaneRecord.AbortRequested.class, name = "abort_requested"),
    @JsonSubTypes.Type(value = LaneRecord.OperationFinished.class, name = "operation_finished"),
    @JsonSubTypes.Type(value = LaneRecord.StepAttempt.class, name = "step_attempt"),
    @JsonSubTypes.Type(value = LaneRecord.ToolStarted.class, name = "tool_started"),
    @JsonSubTypes.Type(value = LaneRecord.QueueEnqueued.class, name = "queue_enqueued"),
    @JsonSubTypes.Type(value = LaneRecord.QueueCancelled.class, name = "queue_cancelled"),
    @JsonSubTypes.Type(value = LaneRecord.WriteDeferred.class, name = "write_deferred"),
    @JsonSubTypes.Type(value = LaneRecord.UsageRecord.class, name = "usage")
})
public sealed interface LaneRecord {

    /** Unique record identifier. */
    String id();

    /** Monotonic sequence number shared by entries, records, lanes and facts. */
    long seq();

    /** The lane this record belongs to. */
    String lane();

    /** When this record was created. */
    Instant timestamp();

    /** The type discriminant (matches the JSON {@code type} property). */
    default String type() {
        return switch (this) {
            case OperationStarted r -> "operation_started";
            case AbortRequested r -> "abort_requested";
            case OperationFinished r -> "operation_finished";
            case StepAttempt r -> "step_attempt";
            case ToolStarted r -> "tool_started";
            case QueueEnqueued r -> "queue_enqueued";
            case QueueCancelled r -> "queue_cancelled";
            case WriteDeferred r -> "write_deferred";
            case UsageRecord r -> "usage";
        };
    }

    /** Rebuild this record with storage-assigned identity fields. */
    default LaneRecord committed(long seq, Instant timestamp) {
        return switch (this) {
            case OperationStarted e -> new OperationStarted(e.id(), seq, e.lane(), timestamp,
                e.sourceLeafId(), e.intent());
            case AbortRequested e -> new AbortRequested(e.id(), seq, e.lane(), timestamp, e.runId());
            case OperationFinished e -> new OperationFinished(e.id(), seq, e.lane(), timestamp,
                e.runId(), e.outcome(), e.error());
            case StepAttempt e -> new StepAttempt(e.id(), seq, e.lane(), timestamp, e.runId(),
                e.step(), e.attempt(), e.resultEntryId(), e.compactionReason());
            case ToolStarted e -> new ToolStarted(e.id(), seq, e.lane(), timestamp, e.runId(),
                e.assistantEntryId(), e.toolIndex(), e.toolCallId(), e.toolName(),
                e.effectiveArgs(), e.resultEntryId(), e.replay());
            case QueueEnqueued e -> new QueueEnqueued(e.id(), seq, e.lane(), timestamp,
                e.queue(), e.runId(), e.target());
            case QueueCancelled e -> new QueueCancelled(e.id(), seq, e.lane(), timestamp,
                e.runId(), e.entryId());
            case WriteDeferred e -> new WriteDeferred(e.id(), seq, e.lane(), timestamp,
                e.runId(), e.target());
            case UsageRecord e -> new UsageRecord(e.id(), seq, e.lane(), timestamp, e.usage(),
                e.cause(), e.runId(), e.entryId(), e.toolCallId(), e.attempt(), e.stopReason());
        };
    }

    // ═══════════════════════════════════════════════════════════
    // Subtypes
    // ═══════════════════════════════════════════════════════════

    /** An operation (run / compaction / navigation) started. */
    record OperationStarted(
        String id,
        long seq,
        String lane,
        Instant timestamp,
        String sourceLeafId,
        Intent intent
    ) implements LaneRecord {

        /** Operation intent payload (aligned with pi's nested {@code intent} object). */
        @com.fasterxml.jackson.annotation.JsonTypeInfo(
            use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME, property = "kind")
        @com.fasterxml.jackson.annotation.JsonSubTypes({
            @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = Run.class, name = "run"),
            @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = Compaction.class, name = "compaction"),
            @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = Navigation.class, name = "navigation")
        })
        public sealed interface Intent permits Run, Compaction, Navigation {}

        /** A run: normalized caller input before {@code before_run}. */
        public record Run(
            List<com.pijava.ai.message.Message> originalPrompt,
            List<com.pijava.agent.entry.ProvisionedEntry<?>> initialMessages,
            String systemPromptOverride,
            Map<String, Object> resumeData
        ) implements Intent {
            public Run {
                originalPrompt = List.copyOf(originalPrompt);
                initialMessages = List.copyOf(initialMessages);
            }
        }

        /** A compaction operation. */
        public record Compaction(
            String customInstructions,
            String resultEntryId
        ) implements Intent {}

        /** A navigation operation. */
        public record Navigation(
            String targetId,
            boolean summarize,
            String customInstructions,
            String label,
            String summaryEntryId
        ) implements Intent {}
    }

    /** An abort was requested. */
    record AbortRequested(
        String id,
        long seq,
        String lane,
        Instant timestamp,
        String runId
    ) implements LaneRecord {}

    /** An operation finished. */
    record OperationFinished(
        String id,
        long seq,
        String lane,
        Instant timestamp,
        String runId,
        OperationOutcome outcome,
        OperationError error
    ) implements LaneRecord {

        /** Structured error detail (aligned with pi's nested {@code error} object). */
        public record OperationError(String code, String message) {}
    }

    /** A single LLM call attempt. */
    record StepAttempt(
        String id,
        long seq,
        String lane,
        Instant timestamp,
        String runId,
        StepKind step,
        int attempt,
        String resultEntryId,
        String compactionReason
    ) implements LaneRecord {}

    /** A tool started executing. */
    record ToolStarted(
        String id,
        long seq,
        String lane,
        Instant timestamp,
        String runId,
        String assistantEntryId,
        int toolIndex,
        String toolCallId,
        String toolName,
        Map<String, Object> effectiveArgs,
        String resultEntryId,
        ReplayKind replay
    ) implements LaneRecord {
        public ToolStarted {
            effectiveArgs = Map.copyOf(effectiveArgs);
        }
    }

    /** An item was enqueued. */
    record QueueEnqueued(
        String id,
        long seq,
        String lane,
        Instant timestamp,
        QueueKind queue,
        String runId,
        com.pijava.agent.entry.ProvisionedEntry<?> target
    ) implements LaneRecord {}

    /** A queue item was cancelled. */
    record QueueCancelled(
        String id,
        long seq,
        String lane,
        Instant timestamp,
        String runId,
        String entryId
    ) implements LaneRecord {}

    /** A write operation was deferred. */
    record WriteDeferred(
        String id,
        long seq,
        String lane,
        Instant timestamp,
        String runId,
        com.pijava.agent.entry.ProvisionedEntry<?> target
    ) implements LaneRecord {}

    /** Token usage was recorded. */
    record UsageRecord(
        String id,
        long seq,
        String lane,
        Instant timestamp,
        Usage usage,
        UsageCause cause,
        String runId,
        String entryId,
        String toolCallId,
        Integer attempt,
        String stopReason
    ) implements LaneRecord {}
}
