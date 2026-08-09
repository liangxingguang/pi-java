package com.pijava.telemetry;

/**
 * A telemetry span that extends {@link TelemetryContext}.
 *
 * <p>Because a span <em>is</em> a context, child spans can be nested
 * naturally: every {@code startSpan} call inside a span body creates a
 * sub-span of the current span.</p>
 */
public interface TelemetrySpan extends TelemetryContext, AutoCloseable {

    /**
     * End this span.  Must be idempotent — calling after the span has
     * already been ended is a no-op.
     */
    @Override
    void close();
}
