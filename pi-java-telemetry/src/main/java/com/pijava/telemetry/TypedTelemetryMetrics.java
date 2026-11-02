package com.pijava.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/** Metric factories bound to the merged metadata of one or more schemas. */
public final class TypedTelemetryMetrics {

    private final TelemetryMetrics metrics;
    private final Map<String, TelemetryMetricDefinition> definitions = new LinkedHashMap<>();

    TypedTelemetryMetrics(TelemetryMetrics metrics, TelemetrySchemaDefinition... schemas) {
        this.metrics = metrics;
        for (var schema : schemas) {
            if (schema.metrics() != null) {
                definitions.putAll(schema.metrics());
            }
        }
    }

    /** Create a counter using the merged definition's description and unit. */
    public TelemetryCounter counter(String name) {
        return metrics.counter(name, instrumentOptions(name));
    }

    /** Create an up-down counter using the merged definition's description and unit. */
    public TelemetryUpDownCounter upDownCounter(String name) {
        return metrics.upDownCounter(name, instrumentOptions(name));
    }

    /** Create a histogram using the merged definition's metadata and boundaries. */
    public TelemetryHistogram histogram(String name) {
        var definition = definitions.get(name);
        var boundaries = definition instanceof TelemetryMetricDefinition.Histogram histogram
            ? histogram.boundaries() : null;
        var options = definition == null ? null
            : new HistogramOptions(definition.description(), definition.unit(), boundaries);
        return metrics.histogram(name, options);
    }

    private InstrumentOptions instrumentOptions(String name) {
        var definition = definitions.get(name);
        return definition == null ? null
            : new InstrumentOptions(definition.description(), definition.unit());
    }
}
