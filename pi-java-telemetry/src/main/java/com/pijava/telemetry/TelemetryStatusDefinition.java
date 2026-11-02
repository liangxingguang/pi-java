package com.pijava.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Serializable status declaration; pi's default status is always {@code ok}. */
public record TelemetryStatusDefinition(String errorWhen) {

    /** Return the fixed default status under pi's {@code default} JSON key. */
    @JsonProperty("default")
    public String defaultStatus() {
        return "ok";
    }
}
