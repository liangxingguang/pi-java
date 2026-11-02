package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.telemetry.InMemoryTelemetryContext;
import com.pijava.telemetry.MetricKind;
import com.pijava.telemetry.RecordedTelemetryEvent;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.SpanStatus;

import org.junit.jupiter.api.Test;

import static com.pijava.ai.protocol.AiTelemetryFixtures.assertNoSecrets;
import static com.pijava.ai.protocol.AiTelemetryFixtures.awaitSettled;
import static com.pijava.ai.protocol.AiTelemetryFixtures.json;
import static com.pijava.ai.protocol.AiTelemetryFixtures.observe;
import static com.pijava.ai.protocol.AiTelemetryFixtures.partial;
import static com.pijava.ai.protocol.AiTelemetryFixtures.spanJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/13 §6.2：观察者的直接驱动面——poll、交错 tool delta、tracing-only、
 * 假时钟时长、pending/deferred 的 counter 门、instrument 缓存。
 */
class AiRequestTelemetryObserverTest {

    @Test
    void boundsPollErrorMessagesIncludingMissingText() {
        for (var missing : List.of(true, false)) {
            var telemetry = new InMemoryTelemetryContext();
            var caller = telemetry.openSpan(new SpanOptions("caller"));
            var message = missing ? null : "x".repeat(500) + "discarded-tail";
            var result = partial(StopReason.ERROR, message);
            var observer = observe(telemetry, caller, true);

            observer.onEvent(new StreamEvent.Start(result));
            observer.onResult(result);
            caller.close();
            awaitSettled(telemetry);

            var span = telemetry.getSpans().get(1);
            assertThat(span.name()).isEqualTo("pi.ai.poll");
            assertThat(span.status()).isEqualTo(new SpanStatus.Error(
                "provider_error", missing ? "" : "x".repeat(500)));
            assertThat(json(span.attributes())).doesNotContain("xxx");
            assertThat(json(span.events())).doesNotContain("xxx");
            assertThat(spanJson(span)).doesNotContain("discarded-tail");
        }
    }

    @Test
    void countsInterleavedToolDeltasIndependently() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var observer = observe(telemetry, caller, false);
        var result = partial(StopReason.STOP, null);
        var end = new StreamEvent.ToolCallEnd(0, "call-secret", "lookup",
            Map.of("value", "argument-secret"), result);

        observer.onEvent(new StreamEvent.Start(result));
        observer.onEvent(new StreamEvent.ToolCallDelta(2, "call-secret", "ab", result));
        observer.onEvent(new StreamEvent.ToolCallDelta(3, "call-secret", "123", result));
        observer.onEvent(new StreamEvent.ToolCallDelta(2, "call-secret", "c", result));
        observer.onEvent(withIndex(end, 3));
        observer.onEvent(withIndex(end, 2));
        observer.onEvent(withIndex(end, 4));
        observer.onResult(result);
        caller.close();
        awaitSettled(telemetry);

        var span = telemetry.getSpans().get(1);
        assertThat(span.events().subList(1, span.events().size()))
            .extracting(RecordedTelemetryEvent::attributes)
            .containsExactly(
                Map.of("type", "tool_call", "index", 3, "chars", 3, "tool", "lookup"),
                Map.of("type", "tool_call", "index", 2, "chars", 3, "tool", "lookup"),
                Map.of("type", "tool_call", "index", 4, "chars", 0, "tool", "lookup"));
        assertNoSecrets(spanJson(span));
    }

    private static StreamEvent.ToolCallEnd withIndex(StreamEvent.ToolCallEnd end, int index) {
        return new StreamEvent.ToolCallEnd(index, end.id(), end.name(), end.arguments(),
            end.namespace(), end.argumentsError(), end.partial());
    }

    @Test
    void tracingOnlySpanRecordsNoMeasurements() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var tracingOnly = AiTelemetryFixtures.tracingOnly(caller);
        var observer = observe(telemetry, tracingOnly, false);

        observer.onEvent(new StreamEvent.Start(partial(StopReason.STOP, null)));
        observer.onResult(partial(StopReason.STOP, null));
        caller.close();
        awaitSettled(telemetry);

        assertThat(telemetry.getSpans()).hasSize(2);
        assertThat(telemetry.getMetrics()).isEmpty();
    }

    @Test
    void recordsMonotonicDurations() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var clock = new AtomicLong(100_000_000L);
        var observer = AiRequestTelemetry.observe(
            "test", "telemetry-test", "model", Optional.of(new ThinkingLevel.High()),
            caller, false, clock::get);
        var result = partial(StopReason.STOP, null);

        clock.set(125_000_000L);
        observer.onEvent(new StreamEvent.Start(result));
        clock.set(175_000_000L);
        observer.onResult(result);
        caller.close();

        var measurements = telemetry.getMetrics();
        assertThat(measurements.get(0).name()).isEqualTo("pi.ai.first_chunk.duration");
        assertThat(measurements.get(0).value()).isEqualTo(25.0);
        assertThat(measurements.get(1).name()).isEqualTo("pi.ai.request.duration");
        assertThat(measurements.get(1).value()).isEqualTo(75.0);
    }

    @Test
    void recordsUsageForPollOnlyWhenFinal() {
        for (var stop : List.of(StopReason.PENDING, StopReason.DEFERRED,
                StopReason.STOP, StopReason.ERROR)) {
            var telemetry = new InMemoryTelemetryContext();
            var caller = telemetry.openSpan(new SpanOptions("caller"));
            var observer = observe(telemetry, caller, true);
            var result = partial(stop, stop == StopReason.ERROR ? "poll-error" : null);

            observer.onEvent(new StreamEvent.Start(result));
            observer.onResult(result);
            caller.close();

            var counters = telemetry.getMetrics().stream()
                .filter(m -> m.kind() == MetricKind.COUNTER).count();
            assertThat(counters).as("poll stop=%s", stop)
                .isEqualTo(stop == StopReason.PENDING || stop == StopReason.DEFERRED ? 0 : 5);
        }
    }

    @Test
    void createsInstrumentsOncePerMetricsObject() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var counting = new AiTelemetryFixtures.CountingMetrics(telemetry);
        var parent = new AiTelemetryFixtures.MetricsOverridingSpan(caller, counting);

        for (int i = 0; i < 3; i++) {
            var observer = AiRequestTelemetry.observe(
                "test", "telemetry-test", "model", Optional.empty(), parent, false,
                System::nanoTime);
            var result = partial(StopReason.STOP, null);
            observer.onEvent(new StreamEvent.Start(result));
            observer.onResult(result);
        }
        caller.close();

        assertThat(counting.histograms()).isEqualTo(2);
        assertThat(counting.counters()).isEqualTo(2);
    }
}
