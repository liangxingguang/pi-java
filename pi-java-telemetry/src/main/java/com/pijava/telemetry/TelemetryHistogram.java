package com.pijava.telemetry;

import java.util.Map;

/** A distribution instrument with an optional span exemplar context. */
@FunctionalInterface
public interface TelemetryHistogram {

    /** Record a value with attributes and an optional exemplar context. */
    void record(double value, Map<String, Object> attributes, TelemetryContext context);

    /** Record a value without attributes or an exemplar. */
    default void record(double value) {
        record(value, Map.of(), null);
    }

    /** Record a value with attributes but no exemplar. */
    default void record(double value, Map<String, Object> attributes) {
        record(value, attributes, null);
    }
}
