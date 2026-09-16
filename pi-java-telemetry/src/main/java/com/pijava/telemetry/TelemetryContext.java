package com.pijava.telemetry;

import java.util.Map;
import java.util.function.Function;

/**
 * A telemetry context capable of starting child spans and recording
 * counters/timings.
 *
 * <p>Implementations may be no-op, logging-only, or full OpenTelemetry
 * exporters. The callback style ensures spans are always closed.</p>
 *
 * <p><b>Port note — extensions over pi, and two structural differences.</b>
 * This interface is a port of pi's {@code packages/telemetry} contract, but pi's
 * {@code TelemetryContext} declares <em>exactly one</em> method
 * ({@code startSpan(options, callback)}).  Everything else here — {@link #openSpan},
 * {@link #recordEvent}, {@link #pushCurrent}/{@link #popCurrent},
 * {@link #recordsPayloads}, {@link #with} and the two metric methods — is a
 * pi-java addition, as are the {@link JsonlFileTelemetry} and
 * {@link OtelTelemetryContext} adapters (pi ships only a no-op adapter plus a
 * test-only in-memory one).  Two differences are <em>structural</em> and cannot
 * be closed: the callback here is synchronous ({@link Function}) where pi returns
 * a {@code Promise}, and a span here is {@link AutoCloseable} where pi's has no
 * {@code end()} because {@code startSpan} owns settlement.  pi's
 * {@code addEvent}/{@code setStatus} are deliberately not ported — see
 * {@link TelemetrySpan}.  Evidence and adjudication: {@code docs/31 §8.28}.</p>
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

    /**
     * Open a span without a callback; the caller is responsible for closing
     * it (which ends the span).  Use for spans that must cross action or
     * thread boundaries.
     */
    default TelemetrySpan openSpan(SpanOptions options) {
        var holder = new Object() { TelemetrySpan span; };
        startSpan(options, span -> {
            holder.span = span;
            return null;
        });
        return holder.span;
    }

    /** Increment a counter metric. Default no-op. */
    default void incrementCounter(String name, long delta) { }

    /** Record a timing metric in milliseconds. Default no-op. */
    default void recordTiming(String name, long durationMs) { }

    /** Return a child context with an additional dimension. Default returns {@code this}. */
    default TelemetryContext with(String key, String value) {
        return this;
    }

    /**
     * Record a structured event line bound to the current span (see {@link
     * #pushCurrent}).  Payload capture (e.g. {@code --trace-payloads}) is an
     * opt-in: exporters that do not record payloads ignore this. Default no-op.
     *
     * @param name    event name (e.g. "llm.payload.request")
     * @param payload structured payload (may contain nested maps/lists)
     */
    default void recordEvent(String name, Map<String, Object> payload) { }

    /**
     * Whether this context actually records event lines.  Callers use it to
     * skip building an expensive payload when recording is disabled (the
     * default). Default {@code false}.
     */
    default boolean recordsPayloads() {
        return false;
    }

    /**
     * Bind a span (opened on this or another thread) as the current span for
     * {@link #recordEvent} on this thread.  Must be paired with {@link
     * #popCurrent} in a finally block. Default no-op.
     *
     * @param span the span to bind
     */
    default void pushCurrent(TelemetrySpan span) { }

    /**
     * Remove a span previously bound with {@link #pushCurrent}.  Only pops
     * if it is the current top (guards against unbalanced pairs). Default
     * no-op.
     *
     * @param span the span to unbind
     */
    default void popCurrent(TelemetrySpan span) { }
}
