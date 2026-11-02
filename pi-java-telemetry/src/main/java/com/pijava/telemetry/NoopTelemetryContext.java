package com.pijava.telemetry;

import java.util.function.Function;

/**
 * A no-op {@link TelemetryContext} that simply invokes the callback
 * without recording any telemetry data.
 */
public final class NoopTelemetryContext implements TelemetryContext {

    /** Shared singleton instance. */
    public static final NoopTelemetryContext INSTANCE = new NoopTelemetryContext();

    private static final NoopSpan NOOP_SPAN = new NoopSpan(NoopTelemetryMetrics.INSTANCE);

    private NoopTelemetryContext() {
        // singleton
    }

    @Override
    public <T> T startSpan(SpanOptions options, Function<? super TelemetrySpan, ? extends T> body) {
        return body.apply(NOOP_SPAN);
    }

    @Override
    public TelemetrySpan openSpan(SpanOptions options) {
        return NOOP_SPAN;
    }

    @Override
    public TelemetryMetrics metrics() {
        return NoopTelemetryMetrics.INSTANCE;
    }

    static <T> T startInertSpan(
        TelemetryMetrics metrics, SpanOptions options, Function<? super TelemetrySpan, ? extends T> body
    ) {
        return body.apply(new NoopSpan(metrics));
    }

    static TelemetrySpan openInertSpan(TelemetryMetrics metrics) {
        return new NoopSpan(metrics);
    }

    private static final class NoopSpan implements TelemetrySpan {
        private final TelemetryMetrics metrics;

        private NoopSpan(TelemetryMetrics metrics) {
            this.metrics = metrics;
        }

        @Override
        public TelemetryMetrics metrics() {
            return metrics;
        }

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
