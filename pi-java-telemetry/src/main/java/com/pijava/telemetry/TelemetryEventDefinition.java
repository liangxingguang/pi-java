package com.pijava.telemetry;

import java.util.Map;

/** Serializable event metadata within a span definition. */
public record TelemetryEventDefinition(
    String description,
    Map<String, TelemetryAttributeDefinition> attributes
) {
    /** Defensively copy event attributes. */
    public TelemetryEventDefinition {
        attributes = Map.copyOf(attributes);
    }
}
