package com.pijava.telemetry;

/** Optional adapter capability for synchronous metric instruments. */
public interface TelemetryMetrics {

    /** Create or retrieve a counter with descriptive metadata. */
    TelemetryCounter counter(String name, InstrumentOptions options);

    /** Create or retrieve an up-down counter with descriptive metadata. */
    TelemetryUpDownCounter upDownCounter(String name, InstrumentOptions options);

    /** Create or retrieve a histogram with descriptive metadata and boundaries. */
    TelemetryHistogram histogram(String name, HistogramOptions options);

    /** Create or retrieve a counter without optional metadata. */
    default TelemetryCounter counter(String name) {
        return counter(name, null);
    }

    /** Create or retrieve an up-down counter without optional metadata. */
    default TelemetryUpDownCounter upDownCounter(String name) {
        return upDownCounter(name, null);
    }

    /** Create or retrieve a histogram without optional metadata. */
    default TelemetryHistogram histogram(String name) {
        return histogram(name, null);
    }
}
