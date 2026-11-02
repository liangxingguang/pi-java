package com.pijava.telemetry;

import java.util.Map;

/** A monotonically increasing counter. */
@FunctionalInterface
public interface TelemetryCounter {

    /** Add a nonnegative value with the supplied attributes. */
    void add(double value, Map<String, Object> attributes);

    /** Add a nonnegative value without attributes. */
    default void add(double value) {
        add(value, Map.of());
    }
}
