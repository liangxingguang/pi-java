package com.pijava.telemetry;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Serializable span vocabulary and metadata. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetrySpanDefinition(
    String description,
    TelemetryParentDefinition parents,
    Map<String, TelemetryAttributeDefinition> startAttributes,
    Map<String, TelemetryAttributeDefinition> endAttributes,
    Map<String, TelemetryEventDefinition> events,
    TelemetryStatusDefinition status
) {
    /** Defensively copy span definition maps. */
    public TelemetrySpanDefinition {
        startAttributes = Map.copyOf(startAttributes);
        endAttributes = Map.copyOf(endAttributes);
        events = events == null ? null : Map.copyOf(events);
    }
}
