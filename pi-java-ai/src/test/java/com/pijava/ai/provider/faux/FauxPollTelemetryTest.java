package com.pijava.ai.provider.faux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.message.Message;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.telemetry.InMemoryTelemetryContext;
import com.pijava.telemetry.MetricKind;
import com.pijava.telemetry.RecordedTelemetryMeasurement;
import com.pijava.telemetry.RecordedTelemetrySpan;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/20 §6.6（关闭 D203）：deferred 取回是 {@code pi.ai.poll} 的第一条生产入口。
 */
class FauxPollTelemetryTest {

    private static FauxProgrammable fauxWith(int pendingFetches) {
        return FauxProvider.programmable(new FauxProviderOptions(
            "faux-api", "faux", List.of(),
            new FauxDeferredOptions(pendingFetches, null), null, null));
    }

    private static DeferredHandle submit(FauxProgrammable faux) {
        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("final"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());
        var options = ApiOptions.withExtra(ApiOptions.defaults(), Map.of("deferred", Boolean.TRUE));
        try (var iter = api.streamBlocking(new StreamRequest(
                faux.getModel(), null,
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))),
                    new Message.UserMessage(List.of(new ContentBlock.TextContent("there"))),
                    new Message.UserMessage(List.of(new ContentBlock.TextContent("again"))),
                    new Message.UserMessage(List.of(new ContentBlock.TextContent("more"))),
                    new Message.UserMessage(List.of(new ContentBlock.TextContent("and more"))),
                    new Message.UserMessage(List.of(new ContentBlock.TextContent("even more"))),
                    new Message.UserMessage(List.of(new ContentBlock.TextContent("and one more"))),
                    new Message.UserMessage(List.of(new ContentBlock.TextContent("last"))),
                    new Message.UserMessage(List.of(new ContentBlock.TextContent("truly last")))),
                List.of(), -1, -1), options)) {
            while (iter.hasNext()) {
                var event = iter.next();
                if (event instanceof StreamEvent.StreamDone done) {
                    return done.partial().deferred();
                }
            }
        }
        throw new AssertionError("no deferred handle");
    }

    private static List<StreamEvent> fetch(ChatApi api, DeferredHandle handle,
                                           ApiOptions options) {
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.fetchDeferredBlocking(
                com.pijava.ai.catalog.ModelInfo.minimal(
                    com.pijava.ai.model.ModelId.of(handle.provider(), handle.modelId())),
                handle, options)) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    private static ApiOptions pollOptions(TelemetrySpan parent) {
        return new ApiOptions("", "", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, parent);
    }

    private static void awaitSettled(InMemoryTelemetryContext telemetry) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (telemetry.getSpans().stream().allMatch(RecordedTelemetrySpan::settled)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("telemetry spans did not settle");
    }

    private static long counters(InMemoryTelemetryContext telemetry) {
        return telemetry.getMetrics().stream()
            .filter(measurement -> measurement.kind() == MetricKind.COUNTER)
            .count();
    }

    @Test
    void aPendingFetchCreatesAPollSpanButDoesNotCountTokens() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var faux = fauxWith(1);
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());
        var handle = submit(faux);
        fetch(api, handle, pollOptions(caller));
        caller.close();
        awaitSettled(telemetry);

        var spans = telemetry.getSpans();
        assertThat(spans).hasSize(2);
        var poll = spans.get(1);
        assertThat(poll.name()).isEqualTo("pi.ai.poll");
        assertThat(poll.attributes()).containsEntry("provider", "faux")
            .containsEntry("model", "faux-1")
            .containsEntry("api", "faux-api")
            .containsEntry("stop", "deferred")
            .doesNotContainKey("reasoning");
        assertThat(counters(telemetry)).isZero();
    }

    @Test
    void theFinalFetchCountsTokens() {
        var telemetry = new InMemoryTelemetryContext();
        var caller = telemetry.openSpan(new SpanOptions("caller"));
        var faux = fauxWith(0);
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());
        var handle = submit(faux);
        fetch(api, handle, pollOptions(caller));
        caller.close();
        awaitSettled(telemetry);

        var poll = telemetry.getSpans().get(1);
        assertThat(poll.name()).isEqualTo("pi.ai.poll");
        assertThat(poll.attributes()).containsEntry("stop", "stop");
        assertThat(counters(telemetry)).isEqualTo(5);
        assertThat(telemetry.getMetrics().stream()
            .filter(m -> m.name().equals("pi.ai.tokens"))
            .map(RecordedTelemetryMeasurement::value)
            .filter(value -> value > 0)
            .count()).isPositive();
    }

    @Test
    void withoutTelemetryTheFetchStillWorks() {
        var faux = fauxWith(0);
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());
        var handle = submit(faux);

        var events = fetch(api, handle, ApiOptions.defaults());

        assertThat(events).isNotEmpty();
        assertThat(events.get(events.size() - 1).partial().stopReason())
            .isEqualTo(com.pijava.ai.message.StopReason.STOP);
    }
}
