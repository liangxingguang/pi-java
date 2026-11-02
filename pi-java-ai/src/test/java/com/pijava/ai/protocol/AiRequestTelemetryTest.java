package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.telemetry.InMemoryTelemetryContext;
import com.pijava.telemetry.MetricKind;
import com.pijava.telemetry.RecordedTelemetryEvent;
import com.pijava.telemetry.RecordedTelemetryMeasurement;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.SpanStatus;

import org.junit.jupiter.api.Test;

import static com.pijava.ai.protocol.AiTelemetryFixtures.assertNoSecrets;
import static com.pijava.ai.protocol.AiTelemetryFixtures.awaitSettled;
import static com.pijava.ai.protocol.AiTelemetryFixtures.done;
import static com.pijava.ai.protocol.AiTelemetryFixtures.drain;
import static com.pijava.ai.protocol.AiTelemetryFixtures.events;
import static com.pijava.ai.protocol.AiTelemetryFixtures.json;
import static com.pijava.ai.protocol.AiTelemetryFixtures.options;
import static com.pijava.ai.protocol.AiTelemetryFixtures.partial;
import static com.pijava.ai.protocol.AiTelemetryFixtures.request;
import static com.pijava.ai.protocol.AiTelemetryFixtures.spanJson;
import static com.pijava.ai.protocol.AiTelemetryFixtures.usageInfo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/13 §6.2：{@code pi.ai.request} 观察者挂在 {@code AbstractChatApi.stream()}
 * 首次订阅上的端到端行为（正常、错误、setup 失败、无终局、多订阅、早关闭、send）。
 */
class AiRequestTelemetryTest {

    @Test
    void observesStreamWithoutChangingEventsOrMetrics() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var sent = events(done());
        var api = new AiTelemetryFixtures.ReplayApi(sent);

        var received = drain(api, request(), options(caller));
        caller.close();
        awaitSettled(telemetry);

        assertThat(received).hasSameSizeAs(sent);
        for (int i = 0; i < sent.size(); i++) {
            assertThat(received.get(i)).isSameAs(sent.get(i));
        }

        var spans = telemetry.getSpans();
        assertThat(spans).hasSize(2);
        var span = spans.get(1);
        assertThat(span.name()).isEqualTo("pi.ai.request");
        assertThat(span.parentId()).isEqualTo(spans.get(0).id());
        assertThat(span.settled()).isTrue();

        assertThat(span.attributes()).containsEntry("provider", "test")
            .containsEntry("api", "telemetry-test")
            .containsEntry("model", "model")
            .containsEntry("reasoning", "high")
            .containsEntry("stop", "stop")
            .containsEntry("usage.input", 11.0)
            .containsEntry("usage.output", 22.0)
            .containsEntry("usage.cache_read", 33.0)
            .containsEntry("usage.cache_write", 44.0)
            .containsEntry("cost.total", 1.0)
            .containsEntry("response.id", "response");
        assertThat(span.status()).isEqualTo(new SpanStatus.Ok());

        assertThat(span.events()).extracting(RecordedTelemetryEvent::name)
            .containsExactly("pi.ai.first_chunk", "pi.ai.block",
                "pi.ai.block", "pi.ai.block");
        assertThat(span.events().get(0).attributes()).isEmpty();
        assertThat(span.events().get(1).attributes()).isEqualTo(
            Map.of("type", "text", "index", 0, "chars", 11));
        assertThat(span.events().get(2).attributes()).isEqualTo(
            Map.of("type", "thinking", "index", 1, "chars", 15));
        assertThat(span.events().get(3).attributes()).isEqualTo(
            Map.of("type", "tool_call", "index", 2, "chars", 27, "tool", "lookup"));

        assertNoSecrets(spanJson(span));

        var measurements = telemetry.getMetrics();
        assertThat(measurements).hasSize(7);
        assertThat(measurements.get(0).name()).isEqualTo("pi.ai.first_chunk.duration");
        assertThat(measurements.get(0).attributes()).isEqualTo(
            Map.of("provider", "test", "model", "model"));
        assertThat(measurements.get(0).unit()).isEqualTo("ms");
        assertThat(measurements.get(1).name()).isEqualTo("pi.ai.request.duration");
        assertThat(measurements.get(1).attributes()).isEqualTo(
            Map.of("provider", "test", "model", "model", "stop", "stop"));
        assertThat(measurements.get(0).context()).isEqualTo(span.context());
        assertThat(measurements.get(1).context()).isEqualTo(span.context());

        assertThat(measurements.subList(2, 6)).extracting(RecordedTelemetryMeasurement::value)
            .containsExactly(11.0, 22.0, 33.0, 44.0);
        assertThat(measurements.subList(2, 6)).extracting(RecordedTelemetryMeasurement::attributes)
            .containsExactly(
                Map.of("provider", "test", "model", "model", "kind", "input"),
                Map.of("provider", "test", "model", "model", "kind", "output"),
                Map.of("provider", "test", "model", "model", "kind", "cache_read"),
                Map.of("provider", "test", "model", "model", "kind", "cache_write"));
        assertThat(measurements.get(6).name()).isEqualTo("pi.ai.cost");
        assertThat(measurements.get(6).value()).isEqualTo(1.0);
        assertThat(measurements.get(6).attributes())
            .isEqualTo(Map.of("provider", "test", "model", "model"));
    }

    @Test
    void reportsErrorAndAbortedWithoutLeakingErrorTextIntoAttributesOrEvents() {
        for (var stop : List.of(StopReason.ERROR, StopReason.ABORTED)) {
            var telemetry = new InMemoryTelemetryContext();
            var caller = telemetry.openSpan(new SpanOptions("caller"));
            var result = partial(stop, "error-secret");
            var api = new AiTelemetryFixtures.ReplayApi(List.of(
                new StreamEvent.Start(result),
                new StreamEvent.StreamError(stop, null, result)));

            drain(api, request(), options(caller));
            caller.close();
            awaitSettled(telemetry);

            var span = telemetry.getSpans().get(1);
            assertThat(span.attributes().get("stop"))
                .isEqualTo(stop == StopReason.ERROR ? "error" : "aborted");
            if (stop == StopReason.ERROR) {
                assertThat(span.status()).isEqualTo(
                    new SpanStatus.Error("provider_error", "error-secret"));
            } else {
                assertThat(span.status()).isEqualTo(new SpanStatus.Ok());
            }
            assertThat(json(span.attributes())).doesNotContain("error-secret");
            assertThat(json(span.events())).doesNotContain("error-secret");
            if (stop == StopReason.ABORTED) {
                assertNoSecrets(spanJson(span));
            }
        }
    }

    @Test
    void observesSetupFailureAsErrorResult() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var api = new AiTelemetryFixtures.ReplayApi(
            List.of(), new IllegalStateException("setup-secret"));

        drain(api, request(), options(caller));
        caller.close();
        awaitSettled(telemetry);

        var span = telemetry.getSpans().get(1);
        assertThat(span.events()).extracting(RecordedTelemetryEvent::name)
            .containsExactly("pi.ai.first_chunk");
        assertThat(span.attributes()).containsEntry("stop", "error")
            .containsEntry("usage.input", 0.0)
            .containsEntry("usage.output", 0.0)
            .containsEntry("usage.cache_read", 0.0)
            .containsEntry("usage.cache_write", 0.0)
            .containsEntry("cost.total", 0.0)
            .doesNotContainKey("response.id");
        assertThat(span.status()).isEqualTo(
            new SpanStatus.Error("provider_error", "setup-secret"));
        assertThat(json(span.attributes())).doesNotContain("setup-secret");
        assertThat(json(span.events())).doesNotContain("setup-secret");

        var counters = telemetry.getMetrics().stream()
            .filter(m -> m.kind() == MetricKind.COUNTER).toList();
        assertThat(counters).hasSize(5);
        assertThat(counters).extracting(RecordedTelemetryMeasurement::value)
            .containsExactly(0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * docs/13 §4.5：{@code StreamDone} 的 usage 回退顺序是
     * {@code partial.usage()} → {@code done.usage()} → 零。
     * ConformanceFixtures 等夹具只在终局事件上带 usage。
     */
    @Test
    void usesTerminalEventUsageWhenPartialCarriesNone() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var result = AiTelemetryFixtures.partialWithoutUsage(StopReason.STOP);
        var terminal = new StreamEvent.StreamDone(
            StopReason.STOP, new StreamEvent.UsageInfo(3, 5, null), result);
        var api = new AiTelemetryFixtures.ReplayApi(
            List.of(new StreamEvent.Start(result), terminal));

        drain(api, request(), options(caller));
        caller.close();
        awaitSettled(telemetry);

        var span = telemetry.getSpans().get(1);
        assertThat(span.attributes()).containsEntry("usage.input", 3.0)
            .containsEntry("usage.output", 5.0)
            .containsEntry("usage.cache_read", 0.0)
            .containsEntry("usage.cache_write", 0.0)
            .containsEntry("cost.total", 0.0);
        assertThat(telemetry.getMetrics().stream()
            .filter(m -> m.name().equals("pi.ai.tokens")))
            .extracting(RecordedTelemetryMeasurement::value)
            .containsExactly(3.0, 5.0, 0.0, 0.0);
    }

    /**
     * docs/13 §4.5：{@code StreamError} 的错误文本回退顺序是
     * {@code partial.errorMessage()} → {@code StreamEvent.StreamError.textOf(err)}。
     */
    @Test
    void usesTerminalEventErrorTextWhenPartialCarriesNone() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var result = AiTelemetryFixtures.partialWithoutUsage(StopReason.ERROR);
        var terminal = new StreamEvent.StreamError(
            StopReason.ERROR, new IllegalStateException("event-secret"), result);
        var api = new AiTelemetryFixtures.ReplayApi(
            List.of(new StreamEvent.Start(result), terminal));

        drain(api, request(), options(caller));
        caller.close();
        awaitSettled(telemetry);

        var span = telemetry.getSpans().get(1);
        assertThat(span.status()).isEqualTo(
            new SpanStatus.Error("provider_error", "event-secret"));
        assertThat(json(span.attributes())).doesNotContain("event-secret");
        assertThat(json(span.events())).doesNotContain("event-secret");
    }

    @Test
    void nullTelemetryIsNoOp() {
        var api = new AiTelemetryFixtures.ReplayApi(events(done()));

        assertThat(drain(api, request(), ApiOptions.defaults())).isNotEmpty();
    }

    @Test
    void closesSpanWithoutEndAttributesWhenPublisherCompletesWithoutTerminal() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var api = new AiTelemetryFixtures.ReplayApi(List.of());

        drain(api, request(), options(caller));
        caller.close();
        awaitSettled(telemetry);

        var span = telemetry.getSpans().get(1);
        assertThat(span.settled()).isTrue();
        assertThat(span.attributes()).containsOnlyKeys(
            "provider", "api", "model", "reasoning");
        assertThat(span.events()).isEmpty();
        assertThat(telemetry.getMetrics()).isEmpty();
    }

    @Test
    void lateSubscriberDoesNotCreateASecondSpan() throws Exception {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var api = new AiTelemetryFixtures.ReplayApi(events(done()));
        var publisher = api.stream(request(), options(caller));

        var first = new CountDownLatch(1);
        publisher.subscribe(latchCollector(first));
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();

        var second = new CountDownLatch(1);
        publisher.subscribe(latchCollector(second));
        assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();

        caller.close();
        awaitSettled(telemetry);
        assertThat(telemetry.getSpans()).hasSize(2);
    }

    private static Flow.Subscriber<StreamEvent> latchCollector(CountDownLatch latch) {
        return new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(StreamEvent event) { }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        };
    }

    @Test
    void earlyConsumerCloseDoesNotEndSpanBeforeProducerTerminates() throws Exception {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var api = new AiTelemetryFixtures.GatedApi();

        var iter = api.streamBlocking(request(), options(caller));
        assertThat(iter.next()).isInstanceOf(StreamEvent.Start.class);
        iter.close();
        api.release();
        caller.close();
        awaitSettled(telemetry);

        var span = telemetry.getSpans().get(1);
        assertThat(span.settled()).isTrue();
        assertThat(span.attributes()).containsEntry("stop", "stop");
    }

    @Test
    void sendProducesExactlyOneRequestSpan() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var api = new AiTelemetryFixtures.ReplayApi(events(done()));

        api.send(request(), options(caller));
        caller.close();
        awaitSettled(telemetry);

        assertThat(telemetry.getSpans()).hasSize(2);
        assertThat(telemetry.getSpans().get(1).name()).isEqualTo("pi.ai.request");
    }
}
