package com.pijava.telemetry;

import com.fasterxml.jackson.annotation.JsonValue;

/** Optional schema hint for attribute cardinality. */
public enum TelemetryCardinality {
    LOW("low"),
    HIGH("high");

    private final String wireName;

    TelemetryCardinality(String wireName) {
        this.wireName = wireName;
    }

    /** Return the schema wire name. */
    @JsonValue
    public String wireName() {
        return wireName;
    }
}
