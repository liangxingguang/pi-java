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
import static org.assertj.core.api.Assertions.assertThatCode;

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

    @Test
    void openSpanEndsOnCloseAndAcceptsLateAttributes() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);

            var span = telemetry.openSpan(new SpanOptions("harness.run", Map.of("lane", "default")));
            span.addAttribute("stopReason", "completed");
            span.close();
            span.close(); // idempotent

            var data = spans.getFinishedSpanItems().get(0);
            assertThat(data.getName()).isEqualTo("harness.run");
            assertThat(data.getAttributes().get(AttributeKey.stringKey("lane"))).isEqualTo("default");
            assertThat(data.getAttributes().get(AttributeKey.stringKey("stopReason"))).isEqualTo("completed");
            assertThat(data.getEndEpochNanos()).isGreaterThan(0);
        }
    }

    @Test
    void openSpanUnderParentLinksParentSpanId() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);

            telemetry.startSpan(new SpanOptions("outer"), outer -> {
                var inner = outer.openSpan(new SpanOptions("inner"));
                inner.close();
                return null;
            });

            var finished = spans.getFinishedSpanItems();
            assertThat(finished).hasSize(2);
            var outer = finished.stream().filter(s -> s.getName().equals("outer")).findFirst().orElseThrow();
            var inner = finished.stream().filter(s -> s.getName().equals("inner")).findFirst().orElseThrow();
            assertThat(inner.getParentSpanId()).isEqualTo(outer.getSpanContext().getSpanId());
        }
    }

    @Test
    void genericMetricsUseDoubleInstrumentsMetadataBoundariesAndExemplars() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);
            telemetry.metrics().counter("bytes", new InstrumentOptions("Encoded bytes", "By"))
                .add(12.5, Map.of("direction", "out"));
            telemetry.metrics().upDownCounter("active").add(-2);
            var histogram = telemetry.metrics().histogram(
                "duration", new HistogramOptions("Duration", "ms", List.of(1.0, 10.0)));
            telemetry.startSpan(new SpanOptions("measured"), span -> {
                histogram.record(5.5, Map.of("method", "read"), span);
                return null;
            });

            var collected = List.copyOf(metrics.collectAllMetrics());
            var counter = collected.stream().filter(m -> m.getName().equals("bytes")).findFirst().orElseThrow();
            var active = collected.stream().filter(m -> m.getName().equals("active")).findFirst().orElseThrow();
            var duration = collected.stream().filter(m -> m.getName().equals("duration")).findFirst().orElseThrow();
            assertThat(counter.getType()).isEqualTo(MetricDataType.DOUBLE_SUM);
            assertThat(active.getType()).isEqualTo(MetricDataType.DOUBLE_SUM);
            assertThat(counter.getDescription()).isEqualTo("Encoded bytes");
            assertThat(counter.getUnit()).isEqualTo("By");
            var counterPoint = counter.getDoubleSumData().getPoints().iterator().next();
            assertThat(counterPoint.getValue()).isEqualTo(12.5);
            assertThat(counterPoint.getAttributes().get(AttributeKey.stringKey("direction"))).isEqualTo("out");

            var durationPoint = duration.getHistogramData().getPoints().iterator().next();
            assertThat(durationPoint.getBoundaries()).containsExactly(1.0, 10.0);
            var spanId = spans.getFinishedSpanItems().get(0).getSpanId();
            assertThat(durationPoint.getExemplars())
                .anySatisfy(exemplar -> assertThat(exemplar.getSpanContext().getSpanId()).isEqualTo(spanId));
        }
    }

    @Test
    void genericMetricsAllowDescriptionAndUnitToBeAbsentIndependently() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);

            assertThatCode(() -> {
                telemetry.metrics().counter("description-only", new InstrumentOptions("Description", null)).add(1);
                telemetry.metrics().counter("unit-only", new InstrumentOptions(null, "By")).add(1);
                telemetry.metrics().histogram(
                    "unitless-histogram", new HistogramOptions("Description", null, List.of(1.0))).record(1);
            }).doesNotThrowAnyException();

            var collected = List.copyOf(metrics.collectAllMetrics());
            assertThat(collected).extracting(metric -> metric.getName())
                .contains("description-only", "unit-only", "unitless-histogram");
        }
    }

    @Test
    void genericMetricsMapArrayAndListAttributes() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);
            telemetry.metrics().counter("arrays").add(1, Map.of(
                "strings", new String[]{"a", "b"},
                "longs", new long[]{1, 2},
                "doubles", new double[]{1.5, 2.5},
                "booleans", new boolean[]{true, false},
                "list", List.of("x", "y")));

            var collected = List.copyOf(metrics.collectAllMetrics());
            var point = collected.stream().filter(metric -> metric.getName().equals("arrays"))
                .findFirst().orElseThrow().getDoubleSumData().getPoints().iterator().next();
            assertThat(point.getAttributes().get(AttributeKey.stringArrayKey("strings")))
                .isEqualTo(List.of("a", "b"));
            assertThat(point.getAttributes().get(AttributeKey.longArrayKey("longs")))
                .isEqualTo(List.of(1L, 2L));
            assertThat(point.getAttributes().get(AttributeKey.doubleArrayKey("doubles")))
                .isEqualTo(List.of(1.5, 2.5));
            assertThat(point.getAttributes().get(AttributeKey.booleanArrayKey("booleans")))
                .isEqualTo(List.of(true, false));
            assertThat(point.getAttributes().get(AttributeKey.stringArrayKey("list")))
                .isEqualTo(List.of("x", "y"));
        }
    }

    @Test
    void startSpanDelegatesToOpenSpan() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = sdk(spans, metrics)) {
            var telemetry = OtelTelemetryContext.create(sdk);

            String result = telemetry.startSpan(new SpanOptions("ai.call"), span -> {
                span.addAttribute("late", "attr");
                return "value";
            });

            assertThat(result).isEqualTo("value");
            var data = spans.getFinishedSpanItems().get(0);
            assertThat(data.getAttributes().get(AttributeKey.stringKey("late"))).isEqualTo("attr");
        }
    }
}
