package com.pijava.telemetry;

import java.util.List;
import java.util.Map;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-20: the OTel adapter records spans, counters, timings, dimensions and
 * error status onto a real SDK wired with in-memory exporters.
 */
class OtelTelemetryContextTest {

    private static OpenTelemetrySdk sdk(InMemorySpanExporter spans, InMemoryMetricReader metrics) {
        var tracer = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build();
        var meter = SdkMeterProvider.builder()
            .registerMetricReader(metrics)
            .build();
        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracer)
            .setMeterProvider(meter)
            .build();
    }

    @Test
    void startSpanRecordsNameAttributesAndEnds() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);

            String result = telemetry.startSpan(
                new SpanOptions("ai.call", Map.of("model", "gpt-5")),
                span -> "done");

            assertThat(result).isEqualTo("done");
            assertThat(spans.getFinishedSpanItems()).hasSize(1);
            var data = spans.getFinishedSpanItems().get(0);
            assertThat(data.getName()).isEqualTo("ai.call");
            assertThat(data.getAttributes().get(AttributeKey.stringKey("model"))).isEqualTo("gpt-5");
            assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(data.getParentSpanId()).isEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());
        }
    }

    @Test
    void startSpanRecordsErrorStatusOnException() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);

            try {
                telemetry.startSpan(new SpanOptions("ai.call"), span -> {
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException expected) {
                // expected
            }

            assertThat(spans.getFinishedSpanItems()).hasSize(1);
            var data = spans.getFinishedSpanItems().get(0);
            assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        }
    }

    @Test
    void nestedSpansLinkToParent() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);

            telemetry.startSpan(new SpanOptions("outer"), outer ->
                outer.startSpan(new SpanOptions("inner"), inner -> null));

            var finished = spans.getFinishedSpanItems();
            assertThat(finished).hasSize(2);
            var outer = finished.stream().filter(s -> s.getName().equals("outer")).findFirst().orElseThrow();
            var inner = finished.stream().filter(s -> s.getName().equals("inner")).findFirst().orElseThrow();
            assertThat(inner.getParentSpanId()).isEqualTo(outer.getSpanContext().getSpanId());
        }
    }

    @Test
    void countersAndTimingsRecordAsMetrics() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);

            telemetry.incrementCounter("harness.turn", 5);
            telemetry.recordTiming("ai.call.latency", 42);

            var collected = List.copyOf(metrics.collectAllMetrics());
            var counter = collected.stream()
                .filter(m -> m.getName().equals("harness.turn")).findFirst().orElseThrow();
            var timing = collected.stream()
                .filter(m -> m.getName().equals("ai.call.latency")).findFirst().orElseThrow();
            assertThat(counter.getType()).isEqualTo(MetricDataType.LONG_SUM);
            assertThat(timing.getType()).isEqualTo(MetricDataType.HISTOGRAM);
        }
    }

    @Test
    void withAttachesDimensionToSubsequentSpansAndMetrics() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var scoped = OtelTelemetryContext.create(sdk).with("session.id", "abc");

            scoped.startSpan(new SpanOptions("ai.call"), span -> null);
            scoped.incrementCounter("harness.turn", 1);

            var data = spans.getFinishedSpanItems().get(0);
            assertThat(data.getAttributes().get(AttributeKey.stringKey("session.id"))).isEqualTo("abc");
        }
    }
}
