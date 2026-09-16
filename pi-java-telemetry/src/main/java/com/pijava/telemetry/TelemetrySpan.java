package com.pijava.telemetry;

/**
 * A telemetry span that extends {@link TelemetryContext}.
 *
 * <p>Because a span <em>is</em> a context, child spans can be nested
 * naturally: every {@code startSpan} call inside a span body creates a
 * sub-span of the current span.</p>
 *
 * <p><b>Port note — extensions over pi, and two deliberate omissions.</b>
 * {@link #close()} and the per-key {@link #addAttribute} are pi-java's shapes:
 * pi's span type has no {@code end()} (its {@code startSpan} owns settlement)
 * and merges a whole attribute bag in one call.  pi's {@code addEvent} and
 * {@code setStatus} are <em>not</em> ported, and that is a decision rather than
 * an oversight: {@code addEvent} would mean something different here, because
 * event data lands as whole-payload rows via
 * {@link TelemetryContext#recordEvent} rather than as small attributes hung off
 * a span ({@code docs/31 §8.25.3}), and status is derived automatically — the
 * vocabulary is {@code ok}/{@code error}, with an aborted run expressed through
 * the {@code outcome} attribute exactly as in pi's schema ({@code docs/31 §8.28}).</p>
 *
 * <p>A child span opened from an already-settled span is <em>inert</em>: pi's
 * adapter contract ignores calls made after settlement, including child
 * creation ({@code docs/31 §8.28.4}).</p>
 */
public interface TelemetrySpan extends TelemetryContext, AutoCloseable {

    /**
     * End this span.  Must be idempotent — calling after the span has
     * already been ended is a no-op.
     */
    @Override
    void close();

    /**
     * Attach an attribute discovered after the span started (e.g. token
     * counts known only at the end).  Null values are ignored.  Default no-op.
     */
    default void addAttribute(String key, Object value) { }
}
