package com.pijava.telemetry;

import com.fasterxml.jackson.annotation.JsonValue;

/** Closed set of telemetry attribute value shapes. */
public enum TelemetryAttributeType {
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    STRING_ARRAY("string[]"),
    NUMBER_ARRAY("number[]"),
    BOOLEAN_ARRAY("boolean[]");

    private final String wireName;

    TelemetryAttributeType(String wireName) {
        this.wireName = wireName;
    }

    /** Return the schema wire name. */
    @JsonValue
    public String wireName() {
        return wireName;
    }
}
