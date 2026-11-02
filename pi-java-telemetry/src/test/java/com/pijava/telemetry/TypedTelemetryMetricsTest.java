package com.pijava.telemetry;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TypedTelemetryMetricsTest {

    @Test
    void mergesSchemasAndForwardsInstrumentMetadata() {
        var telemetry = new InMemoryTelemetryContext();
        var traffic = new TelemetrySchemaDefinition(1, Map.of(), Map.of(
            "network.bytes", new TelemetryMetricDefinition.Counter("Encoded bytes", "By", Map.of()),
            "network.active", new TelemetryMetricDefinition.UpDownCounter("Open links", null, Map.of())));
        var latency = new TelemetrySchemaDefinition(1, Map.of(), Map.of(
            "call.duration", new TelemetryMetricDefinition.Histogram(
                "Call duration", "ms", Map.of(), List.of(1.0, 10.0, 100.0))));
        var metrics = TelemetrySchemas.createTypedMetrics(telemetry.metrics(), traffic, latency);

        metrics.counter("network.bytes").add(128, Map.of("direction", "out"));
        metrics.upDownCounter("network.active").add(1);
        metrics.upDownCounter("network.active").add(-1);
        metrics.histogram("call.duration").record(12);

        var measurements = telemetry.getMetrics();
        assertThat(measurements.get(0).description()).isEqualTo("Encoded bytes");
        assertThat(measurements.get(0).unit()).isEqualTo("By");
        assertThat(measurements.get(1).description()).isEqualTo("Open links");
        assertThat(measurements.get(3).description()).isEqualTo("Call duration");
        assertThat(measurements.get(3).unit()).isEqualTo("ms");
        assertThat(measurements.get(3).boundaries()).containsExactly(1.0, 10.0, 100.0);
    }

    @Test
    void duplicateAndUnknownNamesDoNotGainRuntimeValidation() {
        var telemetry = new InMemoryTelemetryContext();
        var first = new TelemetrySchemaDefinition(1, Map.of(), Map.of(
            "shared", new TelemetryMetricDefinition.Counter("First", null, Map.of())));
        var second = new TelemetrySchemaDefinition(1, Map.of(), Map.of(
            "shared", new TelemetryMetricDefinition.Counter("Second", null, Map.of())));
        var metrics = TelemetrySchemas.createTypedMetrics(telemetry.metrics(), first, second);

        assertThatCode(() -> {
            metrics.counter("shared").add(1);
            metrics.upDownCounter("unknown").add(1);
        }).doesNotThrowAnyException();

        assertThat(telemetry.getMetrics().get(0).description()).isEqualTo("Second");
        assertThat(telemetry.getMetrics().get(1).description()).isNull();
    }
}
