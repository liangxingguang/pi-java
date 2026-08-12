package com.pijava.telemetry;

import java.util.function.Function;

/**
 * A telemetry context capable of starting child spans and recording
 * counters/timings.
 *
 * <p>Implementations may be no-op, logging-only, or full OpenTelemetry
 * exporters. The callback style ensures spans are always closed.</p>
 */
@FunctionalInterface
public interface TelemetryContext {

    /**
     * Start a new span, invoke the callback with it, and close the span
     * when the callback returns (or throws).
     *
     * @param <T>     the result type
     * @param options span name and attributes
     * @param body    work to perform within the span
     * @return the value returned by {@code body}
     */
    <T> T startSpan(SpanOptions options, Function<? super TelemetrySpan, ? extends T> body);

    /** Increment a counter metric. Default no-op. */
    default void incrementCounter(String name, long delta) { }

    /** Record a timing metric in milliseconds. Default no-op. */
    default void recordTiming(String name, long durationMs) { }

    /** Return a child context with an additional dimension. Default returns {@code this}. */
    default TelemetryContext with(String key, String value) {
        return this;
    }
}
