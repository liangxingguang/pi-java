package com.pijava.telemetry;

import java.util.function.Function;

/**
 * A no-op {@link TelemetryContext} that simply invokes the callback
 * without recording any telemetry data.
 */
public final class NoopTelemetryContext implements TelemetryContext {

    /** Shared singleton instance. */
    public static final NoopTelemetryContext INSTANCE = new NoopTelemetryContext();

    private static final NoopSpan NOOP_SPAN = new NoopSpan();

    private NoopTelemetryContext() {
        // singleton
    }

    @Override
    public <T> T startSpan(SpanOptions options, Function<? super TelemetrySpan, ? extends T> body) {
        return body.apply(NOOP_SPAN);
    }

    private static final class NoopSpan implements TelemetrySpan {
        @Override
        public void close() {
            // no-op
        }

        @Override
        public <T> T startSpan(SpanOptions options, Function<? super TelemetrySpan, ? extends T> body) {
            return body.apply(this);
        }
    }
}
