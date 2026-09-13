package com.pijava.agent.harness;

import java.util.List;

/**
 * Handle to a specific lane. Delegates to {@link AgentHarness} with lane name context.
 * All operations are scoped to this lane.
 */
public class LaneHandle {
    private final String laneName;
    private final AgentHarness harness;

    LaneHandle(String laneName, AgentHarness harness) {
        this.laneName = laneName;
        this.harness = harness;
    }

    /** Lane identifier. */
    public String name() {
        return laneName;
    }

    /** Run a prompt to completion on this lane (blocking). */
    public PiLaneEngine.RunOutcome run(String prompt) {
        return harness.prompt(laneName, prompt, List.of(), null);
    }

    /** Abort the current run on this lane. */
    public void abort() {
        harness.abort(laneName);
    }

    /** Get a point-in-time snapshot of this lane. */
    public LaneSnapshot snapshot() {
        return harness.snapshot(laneName);
    }

    /** Enqueue a steer prompt. */
    public String steer(String prompt) {
        return harness.steer(laneName, prompt);
    }

    /** Enqueue a follow-up prompt. */
    public String followUp(String prompt) {
        return harness.followUp(laneName, prompt);
    }

    /** Enqueue a next-run prompt. */
    public String nextRun(String prompt) {
        return harness.nextRun(laneName, prompt);
    }

    /** Cancel queued items of the given type. */
    public void cancelQueued(String queueType) {
        harness.cancelQueued(laneName, queueType);
    }
}
