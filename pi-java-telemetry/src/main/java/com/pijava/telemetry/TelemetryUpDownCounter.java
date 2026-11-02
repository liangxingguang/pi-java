package com.pijava.telemetry;

import java.util.Map;

/** An additive instrument that accepts positive and negative measurements. */
@FunctionalInterface
public interface TelemetryUpDownCounter {

    /** Add a positive or negative value with the supplied attributes. */
    void add(double value, Map<String, Object> attributes);

    /** Add a positive or negative value without attributes. */
    default void add(double value) {
        add(value, Map.of());
    }
}
