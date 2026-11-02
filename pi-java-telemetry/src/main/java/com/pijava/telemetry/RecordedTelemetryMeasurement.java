package com.pijava.telemetry;

import java.util.List;
import java.util.Map;

/** One raw, unaggregated metric measurement and its optional span exemplar. */
public record RecordedTelemetryMeasurement(
    MetricKind kind,
    String name,
    String description,
    String unit,
    List<Double> boundaries,
    double value,
    Map<String, Object> attributes,
    Map<String, String> context
) {

    /** Defensively copy all mutable measurement components. */
    public RecordedTelemetryMeasurement {
        boundaries = boundaries == null ? null : List.copyOf(boundaries);
        attributes = TelemetryAttributes.copy(attributes);
        context = context == null ? null : Map.copyOf(context);
    }
}
