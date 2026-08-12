package com.pijava.agent.harness;

/**
 * Declares the steer/followUp/nextRun queue scheduling API.
 *
 * <p>Phase 2c only declares the signatures; queue consumption is Phase 3,
 * so every method throws {@link UnsupportedOperationException}. Package-private
 * — only {@code AgentHarness} delegates to it.</p>
 */
final class QueueManager {

    /** Enqueue a steer prompt. Phase 3. */
    String steer(String laneName, String prompt) {
        throw new UnsupportedOperationException("steer queue is not implemented (Phase 3)");
    }

    /** Enqueue a follow-up prompt. Phase 3. */
    String followUp(String laneName, String prompt) {
        throw new UnsupportedOperationException("followUp queue is not implemented (Phase 3)");
    }

    /** Enqueue a next-run prompt. Phase 3. */
    String nextRun(String laneName, String prompt) {
        throw new UnsupportedOperationException("nextRun queue is not implemented (Phase 3)");
    }

    /** Cancel queued items of the given type. Phase 3. */
    void cancelQueued(String laneName, String queueType) {
        throw new UnsupportedOperationException("cancelQueued is not implemented (Phase 3)");
    }
}
