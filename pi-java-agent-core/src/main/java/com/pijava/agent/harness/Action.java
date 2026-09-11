package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;

/**
 * A pending action that the harness will execute (manual drive mode).
 *
 * <p>Phase 2a subtypes: {@link StreamAssistant}, {@link ApplyPendingWrite},
 * {@link TryFinishRun}. {@link ExecuteTool} is declared for Phase 2b
 * but never returned in Phase 2a. Phase 3 adds {@link ExecuteToolBatch},
 * {@link ConsumeQueueItem}, {@link FinishOperation}.</p>
 *
 * <p>Aligned with pi's {@code ActionInfo} union type (agent-harness.ts):
 * {@link ApplyPendingWrite} ~ apply_pending_write, {@link ConsumeQueueItem} ~
 * consume_queue_item, {@link FinishOperation} ~ finish_operation.</p>
 */
public sealed interface Action {

    /**
     * Call the LLM and consume the event stream.
     *
     * @param step    "assistant" (Phase 2a) | "compaction" | "branch_summary" (Phase 2c)
     * @param attempt retry attempt number (0-indexed)
     */
    record StreamAssistant(
        String step,
        int attempt
    ) implements Action {}

    /**
     * Persist a provisioned entry to storage (pi {@code apply_pending_write}).
     * The entry was already created in pendingWrites; this action
     * only identifies it by type + ID.
     *
     * @param entryType "message" | "thinking_level_change" | ...
     * @param entryId   the entry's unique ID
     */
    record ApplyPendingWrite(
        String entryType,
        String entryId
    ) implements Action {}

    /**
     * Try to end the current run.
     * May be rejected if the lane state is not ready.
     *
     * @param outcome "completed" | "tool_use" | "error"
     */
    record TryFinishRun(
        String outcome
    ) implements Action {}

    /**
     * Execute a tool call (Phase 2b).
     * Declared now because Java sealed types require all subtypes at compile time.
     */
    record ExecuteTool(
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
    ) implements Action {
        /** Defensively copies {@code arguments}. */
        public ExecuteTool {
            arguments = Map.copyOf(arguments);
        }
    }

    /**
     * Execute a batch of tool calls from one assistant turn (Phase 3).
     * Used by the harness when {@link ToolExecution.Parallel} is active and
     * the turn produced more than one tool call; the calls run in parallel on
     * a virtual-thread executor.
     *
     * @param calls tool calls in declaration order (defensive copy)
     */
    record ExecuteToolBatch(
        List<ExecuteTool> calls
    ) implements Action {
        /** Defensively copies {@code calls}. */
        public ExecuteToolBatch {
            calls = List.copyOf(calls);
        }
    }

    /**
     * Consume drained steer/followUp queue items (pi {@code consume_queue_item}).
     * The items were already dequeued by {@code QueueManager.drain*} honoring
     * the QueueMode (one-at-a-time or all); this action starts the next run
     * from them, merged into a single user message.
     *
     * @param queue "steer" | "followUp" (pi has no nextRun queue; pi-java's
     *              nextRun queue consumes with the followUp semantics)
     * @param items the drained items (defensive copy)
     */
    record ConsumeQueueItem(
        String queue,
        List<LaneInfo.QueuedItem> items
    ) implements Action {
        /** Defensively copies {@code items}. */
        public ConsumeQueueItem {
            items = List.copyOf(items);
        }
    }

    /**
     * Finish the current operation (pi {@code finish_operation}).
     * Writes the operation_finished record and clears the lane's current
     * operation. Distinguished from {@link TryFinishRun}, which only decides
     * whether a single run ends; finish_operation is the terminal action for
     * the whole operation, including abort/decline outcomes.
     *
     * @param outcome "completed" | "declined" | "failed" | "aborted"
     *                ({@link com.pijava.agent.record.OperationOutcome} value)
     * @param stop    when true, the drive loop ends here instead of chaining a
     *                follow-up run — the shouldStopAfterTurn hook returned true,
     *                or the run was terminated by a tool outcome. Queued
     *                follow-ups stay in the queue for a future drive (L3).
     */
    record FinishOperation(
        String outcome,
        boolean stop
    ) implements Action {
        /** Normal terminal outcome: the next IDLE peekAction may chain a follow-up. */
        public FinishOperation(String outcome) {
            this(outcome, false);
        }
    }
}
