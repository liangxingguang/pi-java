package com.pijava.agent.harness;

/**
 * Controls how many queued user messages are injected when the agent loop
 * reaches a queue drain point (aligned with pi's {@code QueueMode}).
 *
 * <p>Phase 3: used by {@link QueueManager} for the {@code steer} and
 * {@code followUp} queues. The JSON settings layer stores the mode as a
 * string ({@code "all" | "one-at-a-time"}); this type is the strong-typed
 * runtime representation.</p>
 */
public sealed interface QueueMode {

    /** Drain and inject every queued message at the drain point. */
    record All() implements QueueMode {}

    /** Drain and inject only the oldest queued message, leaving the rest queued. */
    record OneAtATime() implements QueueMode {}

    /** Default mode: one-at-a-time (aligned with pi). */
    static QueueMode defaultMode() {
        return new OneAtATime();
    }
}
