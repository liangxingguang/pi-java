package com.pijava.telemetry;

import java.util.Map;

/**
 * A telemetry span that extends {@link TelemetryContext}.
 *
 * <p>Because a span <em>is</em> a context, child spans can be nested
 * naturally: every {@code startSpan} call inside a span body creates a
 * sub-span of the current span.</p>
 *
 * <p><b>Port note.</b> The contract mirrors pi's {@code TelemetrySpan}
 * ({@code telemetry/src/index.ts:18-22}): {@link #addEvent},
 * {@link #setAttributes} and {@link #setStatus} are pi's own three methods
 * (ported in docs/22 §2, superseding the earlier 原 docs/31 §8.25.3/§8.28
 * &quot;not ported&quot; ruling — the port of pi's {@code memory.ts} and of the
 * adapter conformance suite requires them). {@link #close()} and the per-key
 * {@link #addAttribute} remain pi-java's shapes: pi's span type has no
 * {@code end()} (its {@code startSpan} owns settlement) and merges a whole
 * attribute bag in one call. The whole-payload sibling of {@code addEvent}
 * is {@link TelemetryContext#recordEvent}: {@code addEvent} hangs small
 * attributes off the span and is never gated, {@code recordEvent} writes
 * whole-payload rows, is {@code --trace-payloads} gated and thread-bound.</p>
 *
 * <p>Calls on an already-settled span are <em>inert</em>: pi's adapter
 * contract ignores them, including child creation
 * ({@code memory.ts:126,142,150,158}; conformance case &quot;makes calls
 * after settlement inert&quot;).</p>
 */
public interface TelemetrySpan extends TelemetryContext, AutoCloseable {

    /**
     * End this span.  Must be idempotent — calling after the span has
     * already been ended is a no-op.
     */
    @Override
    void close();

    /**
     * pi {@code index.ts:19}: record an event on this span (small
     * attributes, span-bound, never payload-gated).  Inert after
     * settlement.  Default no-op.
     */
    default void addEvent(String name, Map<String, Object> attributes) { }

    /**
     * pi {@code index.ts:20}: merge a whole attribute bag into the span
     * (null values are skipped ≙ pi skipping {@code undefined}).  Inert
     * after settlement.  Default no-op.
     */
    default void setAttributes(Map<String, Object> attributes) { }

    /**
     * pi {@code index.ts:21}: set an explicit status.  An explicit status
     * wins over the automatic error derivation on failure
     * ({@code memory.ts:96} {@code !span.explicitStatus}); last write wins.
     * Inert after settlement.  Default no-op.
     */
    default void setStatus(SpanStatus status) { }

    /**
     * Per-key convenience (pi-java extension): forwards to
     * {@link #setAttributes}.  Null values are ignored.
     */
    default void addAttribute(String key, Object value) {
        if (value != null) {
            setAttributes(Map.of(key, value));
        }
    }
}
