package com.pijava.telemetry;

/** Shared instruments that never inspect or retain their inputs. */
public final class NoopTelemetryMetrics implements TelemetryMetrics {

    /** Shared noop metrics capability. */
    public static final NoopTelemetryMetrics INSTANCE = new NoopTelemetryMetrics();

    private static final TelemetryCounter COUNTER = (value, attributes) -> { };
    private static final TelemetryUpDownCounter UP_DOWN_COUNTER = (value, attributes) -> { };
    private static final TelemetryHistogram HISTOGRAM = (value, attributes, context) -> { };

    private NoopTelemetryMetrics() {
    }

    @Override
    public TelemetryCounter counter(String name, InstrumentOptions options) {
        return COUNTER;
    }

    @Override
    public TelemetryUpDownCounter upDownCounter(String name, InstrumentOptions options) {
        return UP_DOWN_COUNTER;
    }

    @Override
    public TelemetryHistogram histogram(String name, HistogramOptions options) {
        return HISTOGRAM;
    }
}
