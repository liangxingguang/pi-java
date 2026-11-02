package com.pijava.telemetry;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Serializable telemetry schema data. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetrySchemaDefinition(
    int version,
    Map<String, TelemetrySpanDefinition> spans,
    Map<String, TelemetryMetricDefinition> metrics
) {
    /** Defensively copy schema maps. */
    public TelemetrySchemaDefinition {
        spans = Map.copyOf(spans);
        metrics = metrics == null ? null : Map.copyOf(metrics);
    }
}
