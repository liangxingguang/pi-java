package com.pijava.telemetry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Generic metric factories and line encoding kept outside the span state machine. */
final class JsonlTelemetryMetrics implements TelemetryMetrics {

    private final Function<String, LinkedHashMap<String, Object>> lines;
    private final Consumer<LinkedHashMap<String, Object>> writer;
    private final Function<TelemetryContext, Map<String, String>> exemplars;

    JsonlTelemetryMetrics(
        Function<String, LinkedHashMap<String, Object>> lines,
        Consumer<LinkedHashMap<String, Object>> writer,
        Function<TelemetryContext, Map<String, String>> exemplars
    ) {
        this.lines = lines;
        this.writer = writer;
        this.exemplars = exemplars;
    }

    @Override
    public TelemetryCounter counter(String name, InstrumentOptions options) {
        String description = options == null ? null : options.description();
        String unit = options == null ? null : options.unit();
        return (value, attributes) -> record(
            MetricKind.COUNTER, name, description, unit, null, value, attributes, null);
    }

    @Override
    public TelemetryUpDownCounter upDownCounter(String name, InstrumentOptions options) {
        String description = options == null ? null : options.description();
        String unit = options == null ? null : options.unit();
        return (value, attributes) -> record(
            MetricKind.UP_DOWN_COUNTER, name, description, unit, null, value, attributes, null);
    }

    @Override
    public TelemetryHistogram histogram(String name, HistogramOptions options) {
        String description = options == null ? null : options.description();
        String unit = options == null ? null : options.unit();
        List<Double> boundaries = options == null || options.boundaries() == null
            ? null : List.copyOf(options.boundaries());
        return (value, attributes, context) -> record(
            MetricKind.HISTOGRAM, name, description, unit, boundaries, value, attributes, context);
    }

    private void record(
        MetricKind kind,
        String name,
        String description,
        String unit,
        List<Double> boundaries,
        double value,
        Map<String, Object> attributes,
        TelemetryContext context
    ) {
        var line = lines.apply("metric");
        line.put("metricKind", kind.wireName());
        line.put("name", name);
        line.put("value", value);
        if (description != null) {
            line.put("description", description);
        }
        if (unit != null) {
            line.put("unit", unit);
        }
        if (boundaries != null) {
            line.put("boundaries", boundaries);
        }
        line.put("attrs", TelemetryAttributes.copy(attributes));
        var exemplar = context == null ? null : exemplars.apply(context);
        if (exemplar != null) {
            line.put("exemplar", exemplar);
        }
        writer.accept(line);
    }
}
