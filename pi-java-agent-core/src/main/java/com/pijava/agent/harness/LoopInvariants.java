package com.pijava.agent.harness;

/**
 * Runtime loop invariants for the agent state machine (docs/20 §4.1, L2-⑥).
 *
 * <p>Active only under {@code -ea}: the checks sit behind Java {@code assert}
 * statements in {@link ActionExecutor#peekAction}, so production builds run
 * with zero overhead. Package-private static so tests can exercise each
 * invariant deterministically on constructed {@link LaneState}s.</p>
 */
final class LoopInvariants {

    private LoopInvariants() {
    }

    /**
     * Whether {@code action} is a legal next action given the (already
     * mutated) {@code lane} state. The seven invariants:
     *
     * <ol>
     *   <li>IDLE returning {@code null} implies no pending tool calls — a call
     *       must never be silently dropped when the run ends.</li>
     *   <li>ASSISTANT actions are only ApplyPendingWrite / ExecuteTool(Batch) /
     *       StreamAssistant.</li>
     *   <li>CHECKPOINT actions are only ApplyPendingWrite / TryFinishRun /
     *       FinishOperation.</li>
     *   <li>A {@code null} action implies no pending writes — no provisioned
     *       entry may be left unpersisted when the run ends.</li>
     *   <li>Never produce a StreamAssistant once the lane is aborted.</li>
     *   <li>ConsumeQueueItem only in IDLE — queue items are consumed only when
     *       the lane is idle (pi consume happens at checkpoint/finish
     *       boundaries; pi-java's steer injection mid-run stays inline).</li>
     *   <li>FinishOperation only in CHECKPOINT — the terminal action is
     *       produced only from the checkpoint phase.</li>
     * </ol>
     */
    static boolean hold(LaneState lane, Action action) {
        // Invariant 5: an abort must cut the loop — no more LLM requests.
        if (action instanceof Action.StreamAssistant
                && lane.abortSignal != null && lane.abortSignal.isAborted()) {
            return false;
        }
        if (action == null) {
            // A run can only end while idle…
            if (!(lane.phase instanceof RunPhase.Idle)) return false;
            // …with no tool call dropped (invariant 1)…
            if (!lane.pendingToolCalls.isEmpty()) return false;
            // …and no entry left unpersisted (invariant 4).
            return lane.pendingWrites.isEmpty();
        }
        return switch (lane.phase) {
            case RunPhase.Assistant a -> action instanceof Action.ApplyPendingWrite
                || action instanceof Action.ExecuteTool
                || action instanceof Action.ExecuteToolBatch
                || action instanceof Action.StreamAssistant;
            case RunPhase.Checkpoint c -> action instanceof Action.ApplyPendingWrite
                || action instanceof Action.TryFinishRun
                || action instanceof Action.FinishOperation;
            case RunPhase.Idle i -> action instanceof Action.ConsumeQueueItem;
        };
    }

    /** Human-readable lane + action snapshot for the assertion message. */
    static String diagnostics(LaneState lane, Action action) {
        return "lane=" + lane.laneName
            + " phase=" + lane.phase
            + " pendingToolCalls=" + lane.pendingToolCalls.size()
            + " pendingWrites=" + lane.pendingWrites.size()
            + " aborted=" + (lane.abortSignal != null && lane.abortSignal.isAborted())
            + " action=" + action;
    }
}
