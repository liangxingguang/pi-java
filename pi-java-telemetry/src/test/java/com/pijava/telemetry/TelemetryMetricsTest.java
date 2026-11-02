package com.pijava.telemetry;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TelemetryMetricsTest {

    @Test
    void recordsRawMeasurementsInOrderWithMetadataAndAttributes() {
        var telemetry = new InMemoryTelemetryContext();
        var bytes = telemetry.metrics().counter(
            "network.bytes", new InstrumentOptions("Encoded bytes", "By"));
        var active = telemetry.metrics().upDownCounter("network.active");
        var duration = telemetry.metrics().histogram(
            "call.duration", new HistogramOptions("Call duration", "ms", List.of(1.0, 10.0)));

        bytes.add(128, Map.of("direction", "out"));
        active.add(1);
        active.add(-1);
        duration.record(12, Map.of("method", "read"));

        var measurements = telemetry.getMetrics();
        assertThat(measurements).extracting(RecordedTelemetryMeasurement::kind)
            .containsExactly(MetricKind.COUNTER, MetricKind.UP_DOWN_COUNTER,
                MetricKind.UP_DOWN_COUNTER, MetricKind.HISTOGRAM);
        assertThat(measurements).extracting(RecordedTelemetryMeasurement::value)
            .containsExactly(128.0, 1.0, -1.0, 12.0);
        assertThat(measurements.get(0).description()).isEqualTo("Encoded bytes");
        assertThat(measurements.get(0).unit()).isEqualTo("By");
        assertThat(measurements.get(0).attributes()).containsEntry("direction", "out");
        assertThat(measurements.get(3).boundaries()).containsExactly(1.0, 10.0);
    }

    @Test
    void recordsSpanCarriersAndHistogramExemplars() {
        var telemetry = new InMemoryTelemetryContext();
        var histogram = telemetry.metrics().histogram("duration");

        telemetry.startSpan(new SpanOptions("parent"), parent -> {
            histogram.record(1, Map.of(), parent);
            return parent.startSpan(new SpanOptions("child"), child -> null);
        });

        var spans = telemetry.getSpans();
        var parent = spans.get(0);
        var child = spans.get(1);
        assertThat(parent.context()).containsKeys(
            "pi-memory-scope", "pi-memory-span", "pi-memory-trace");
        assertThat(parent.parentContext()).isNull();
        assertThat(child.parentContext()).isEqualTo(parent.context());
        assertThat(child.context()).containsEntry(
            "pi-memory-trace", parent.context().get("pi-memory-trace"));
        assertThat(telemetry.getMetrics().get(0).context()).isEqualTo(parent.context());
        assertThat(telemetry.metrics().histogram("duration")).isNotNull();
        telemetry.metrics().histogram("duration").record(2);
        assertThat(telemetry.getMetrics().get(1).context()).isNull();
    }

    @Test
    void returnsDetachedMeasurementSnapshots() {
        var telemetry = new InMemoryTelemetryContext();
        var boundaries = new ArrayList<>(List.of(1.0, 2.0));
        var histogram = telemetry.metrics().histogram(
            "duration", new HistogramOptions(null, null, boundaries));
        boundaries.add(3.0);
        telemetry.startSpan(new SpanOptions("request"), span -> {
            histogram.record(1, Map.of("tags", new String[]{"original"}), span);
            return null;
        });

        var first = telemetry.getMetrics().get(0);
        ((String[]) first.attributes().get("tags"))[0] = "mutated";

        var second = telemetry.getMetrics().get(0);
        assertThat(second.boundaries()).containsExactly(1.0, 2.0);
        assertThat((String[]) second.attributes().get("tags")).containsExactly("original");
        assertThat(second.context()).isEqualTo(telemetry.getSpans().get(0).context());
    }

    @Test
    void measurementSnapshotsCopyEveryJavaArrayShape() {
        var telemetry = new InMemoryTelemetryContext();
        var codes = new int[]{1};
        telemetry.metrics().counter("codes").add(1, Map.of("codes", codes));
        codes[0] = 2;

        var first = telemetry.getMetrics().get(0);
        ((int[]) first.attributes().get("codes"))[0] = 3;

        var second = telemetry.getMetrics().get(0);
        assertThat((int[]) second.attributes().get("codes")).containsExactly(1);
    }

    @Test
    void exemplarCarrierSurvivesAcrossRecorderInstances() {
        var source = new InMemoryTelemetryContext();
        var recorder = new InMemoryTelemetryContext();
        var histogram = recorder.metrics().histogram("duration");

        source.startSpan(new SpanOptions("request"), span -> {
            histogram.record(1, Map.of(), span);
            return null;
        });

        assertThat(recorder.getMetrics().get(0).context())
            .isEqualTo(source.getSpans().get(0).context());
    }

    @Test
    void noopSharesInstrumentsWithoutInspectingPayloads() {
        var metrics = NoopTelemetryContext.INSTANCE.metrics();
        var counter = metrics.counter("bytes", new InstrumentOptions(null, "By"));
        var active = metrics.upDownCounter("active");
        var histogram = metrics.histogram("duration");
        Map<String, Object> unreadable = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw new AssertionError("noop inspected attributes");
            }
        };

        assertThat(metrics).isSameAs(NoopTelemetryMetrics.INSTANCE);
        assertThat(metrics.counter("other")).isSameAs(counter);
        assertThat(metrics.upDownCounter("other")).isSameAs(active);
        assertThat(metrics.histogram("other")).isSameAs(histogram);
        assertThatCode(() -> {
            counter.add(1, unreadable);
            active.add(-1, unreadable);
            histogram.record(1, unreadable, NoopTelemetryContext.INSTANCE);
        }).doesNotThrowAnyException();
    }

    @Test
    void settledChildrenStayInertButRetainMetricsCapability() {
        var telemetry = new InMemoryTelemetryContext();
        var parent = telemetry.openSpan(new SpanOptions("parent"));
        parent.close();
        var childMetrics = new AtomicReference<TelemetryMetrics>();
        var grandchildMetrics = new AtomicReference<TelemetryMetrics>();

        parent.startSpan(new SpanOptions("child"), child -> {
            childMetrics.set(child.metrics());
            child.startSpan(new SpanOptions("grandchild"), grandchild -> {
                grandchildMetrics.set(grandchild.metrics());
                return null;
            });
            return null;
        });

        assertThat(childMetrics.get()).isSameAs(telemetry.metrics());
        assertThat(grandchildMetrics.get()).isSameAs(telemetry.metrics());
        assertThat(parent.openSpan(new SpanOptions("open-child")).metrics())
            .isSameAs(telemetry.metrics());
        assertThat(telemetry.getSpans()).hasSize(1);
    }
}
