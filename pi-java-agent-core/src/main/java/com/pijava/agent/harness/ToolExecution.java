package com.pijava.agent.harness;

/**
 * Tool execution mode for a single assistant turn with multiple tool calls
 * (aligned with pi's {@code ToolExecutionMode}).
 *
 * <p>Phase 3: {@code Parallel} runs the calls of one turn on a virtual-thread
 * executor (StructuredTaskScope is a preview API in JDK 25, so it is avoided);
 * {@code Sequential} executes them one at a time (debug/compatibility
 * fallback).</p>
 */
public sealed interface ToolExecution {

    /** Execute tool calls one at a time, in declaration order. */
    record Sequential() implements ToolExecution {}

    /** Execute tool calls of the same turn in parallel. */
    record Parallel() implements ToolExecution {}

    /** Default mode: parallel (aligned with pi's agent default). */
    static ToolExecution defaultMode() {
        return new Parallel();
    }
}
