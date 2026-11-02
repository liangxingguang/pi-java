package com.pijava.telemetry;

import com.fasterxml.jackson.annotation.JsonValue;

/** The closed set of generic metric instrument kinds. */
public enum MetricKind {
    COUNTER("counter"),
    UP_DOWN_COUNTER("upDownCounter"),
    HISTOGRAM("histogram");

    private final String wireName;

    MetricKind(String wireName) {
        this.wireName = wireName;
    }

    /** Return the lower-camel wire name used by schemas and JSONL. */
    @JsonValue
    public String wireName() {
        return wireName;
    }
}
