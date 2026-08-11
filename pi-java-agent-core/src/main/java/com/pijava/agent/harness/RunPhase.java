package com.pijava.agent.harness;

/**
 * Run-phase state for {@link AgentHarness} lanes.
 *
 * <p>Package-private — internal to the harness. Java-idiomatic
 * encoding of pi's implicit phase derivation from {@code LaneState.operation}.</p>
 */
sealed interface RunPhase {
    record Idle() implements RunPhase {}
    record Assistant() implements RunPhase {}
    record Checkpoint() implements RunPhase {}

    RunPhase IDLE = new Idle();
    RunPhase ASSISTANT = new Assistant();
    RunPhase CHECKPOINT = new Checkpoint();
}
