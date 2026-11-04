package com.pijava.ai.provider.faux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/20 §6：deferred 提交、取回、取消与 D212 的端到端往返。
 */
class FauxDeferredTest {

    private static FauxProgrammable fauxWith(FauxDeferredOptions deferred) {
        return FauxProvider.programmable(new FauxProviderOptions(
            "faux-api", "faux", List.of(), deferred, null, null));
    }

    private static ApiOptions deferredOptions(Map<String, Object> extra) {
        var map = new LinkedHashMap<String, Object>(extra);
        map.put("deferred", Boolean.TRUE);
        return ApiOptions.withExtra(ApiOptions.defaults(), map);
    }

    private static StreamRequest request(FauxProgrammable faux) {
        return new StreamRequest(faux.getModel(), null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hello"))),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("again"))),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("more"))),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("and more"))),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("even more")))),
            List.of(), -1, -1);
    }

    private static List<StreamEvent> drain(ChatApi api, StreamRequest request, ApiOptions options) {
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request, options)) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    private static List<StreamEvent> fetch(ChatApi api, DeferredHandle handle) {
        return fetch(api, handle, ApiOptions.defaults());
    }

    private static List<StreamEvent> fetch(ChatApi api, DeferredHandle handle,
                                           ApiOptions options) {
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.fetchDeferredBlocking(
                ModelInfo.minimal(
                    com.pijava.ai.model.ModelId.of(handle.provider(), handle.modelId())),
                handle, options)) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    private static String textOf(StreamEvent event) {
        return event.partial().content().stream()
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(ContentBlock.TextContent.class::cast)
            .map(ContentBlock.TextContent::text)
            .reduce("", String::concat);
    }

    @Test
    void submissionEmitsStartAndDoneCarryingTheHandle() {
        var faux = fauxWith(new FauxDeferredOptions(0, 250L));
        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("final"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());

        var events = drain(api, request(faux), deferredOptions(Map.of()));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Start.class);
        var done = (StreamEvent.StreamDone) events.get(1);
        assertThat(done.partial().stopReason()).isEqualTo(StopReason.DEFERRED);
        assertThat(done.partial().content()).isEmpty();
        var handle = done.partial().deferred();
        assertThat(handle.provider()).isEqualTo("faux");
        assertThat(handle.modelId()).isEqualTo("faux-1");
        assertThat(handle.api()).isEqualTo(faux.api());
        assertThat(handle.id()).startsWith("deferred:");
        assertThat(handle.pollAfterMs()).isEqualTo(250L);
        assertThat(handle.expiresAt()).isNull();
        assertThat(handle.data()).isNull();
        assertThat(faux.state().callCount()).isEqualTo(1);
        assertThat(faux.state().deferredFetchCount()).isZero();
    }

    @Test
    void pendingFetchesReturnTheDeferredMessageBeforeTheFinalOne() {
        var faux = fauxWith(new FauxDeferredOptions(1, null));
        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("final"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());
        var submitted = (StreamEvent.StreamDone) drain(api,
            request(faux), deferredOptions(Map.of())).get(1);
        var handle = submitted.partial().deferred();

        var first = fetch(api, handle);
        var second = fetch(api, handle);

        assertThat(first.get(first.size() - 1).partial().stopReason())
            .isEqualTo(StopReason.DEFERRED);
        assertThat(second.get(second.size() - 1).partial().stopReason())
            .isEqualTo(StopReason.STOP);
        assertThat(textOf(second.get(second.size() - 1))).isEqualTo("final");
        assertThat(second.get(second.size() - 1).partial().usage()).isNotNull();
        assertThat(faux.state().callCount()).isEqualTo(1);
        assertThat(faux.state().deferredFetchCount()).isEqualTo(2);
    }

    @Test
    void anUnknownHandleFailsInStreamWithThePiText() {
        var faux = fauxWith(FauxDeferredOptions.defaults());
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());
        var bogus = new DeferredHandle("faux", "faux-1", faux.api(),
            "deferred:missing", null, null, null);

        var events = fetch(api, bogus);

        assertThat(events.get(events.size() - 1)).isInstanceOf(StreamEvent.StreamError.class);
        assertThat(events.get(events.size() - 1).partial().errorMessage())
            .isEqualTo("Unknown faux deferred response: deferred:missing");
        assertThat(faux.state().deferredFetchCount()).isEqualTo(1);
    }

    @Test
    void cancellingRecordsTheHandleAndMakesTheFetchFail() {
        var faux = fauxWith(FauxDeferredOptions.defaults());
        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("final"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());
        var submitted = (StreamEvent.StreamDone) drain(api,
            request(faux), deferredOptions(Map.of())).get(1);
        var handle = submitted.partial().deferred();

        api.cancelDeferred(ModelInfo.minimal(com.pijava.ai.model.ModelId.of("faux", "faux-1")),
            handle, ApiOptions.defaults());
        var events = fetch(api, handle);

        assertThat(events.get(events.size() - 1).partial().errorMessage())
            .isEqualTo("Faux deferred response was cancelled: " + handle.id());
        assertThat(faux.state().cancelledDeferred()).containsExactly(handle);
    }

    @Test
    void aThrowingDeferredFactoryFailsInStreamAtFetchTime() {
        var faux = fauxWith(FauxDeferredOptions.defaults());
        faux.setResponses(List.of(FauxResponseStep.of((context, options, state, model) -> {
            throw new IllegalStateException("deferred boom");
        })));
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());
        var submitted = (StreamEvent.StreamDone) drain(api,
            request(faux), deferredOptions(Map.of())).get(1);

        var events = fetch(api, submitted.partial().deferred());

        assertThat(events.get(events.size() - 1)).isInstanceOf(StreamEvent.StreamError.class);
        assertThat(events.get(events.size() - 1).partial().errorMessage())
            .isEqualTo("deferred boom");
    }

    @Test
    void onResponseHookRunsForSubmitFetchAndCancel() {
        var calls = new ArrayList<String>();
        var hook = (ProviderHooks.ResponseHook) (status, headers, model) ->
            calls.add(status + ":" + model.id().modelName() + ":" + headers.size());
        var extra = new LinkedHashMap<String, Object>();
        extra.put(ProviderHooks.ON_RESPONSE, hook);
        var faux = fauxWith(FauxDeferredOptions.defaults());
        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("final"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());

        var submitted = (StreamEvent.StreamDone) drain(api, request(faux),
            deferredOptions(extra)).get(1);
        fetch(api, submitted.partial().deferred(),
            ApiOptions.withExtra(ApiOptions.defaults(), extra));
        api.cancelDeferred(faux.getModel(), submitted.partial().deferred(),
            ApiOptions.withExtra(ApiOptions.defaults(), extra));

        assertThat(calls).containsExactly(
            "200:faux-1:0", "200:faux-1:0", "200:faux-1:0");
    }

    @Test
    void d212ProductionToFetchToRebuildRoundTrips() {
        var faux = fauxWith(FauxDeferredOptions.defaults());
        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("persisted final"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());

        var persisted = (Message.AssistantMessage) api.send(request(faux),
            deferredOptions(Map.of()));
        assertThat(persisted.stopReason()).isEqualTo(StopReason.DEFERRED);
        assertThat(persisted.deferred()).isNotNull();
        assertThat(persisted.api()).isEqualTo("faux-api");
        assertThat(persisted.provider()).isEqualTo("faux");
        assertThat(persisted.model()).isEqualTo("faux-1");

        var events = fetch(api, persisted.deferred());

        var done = (StreamEvent.StreamDone) events.get(events.size() - 1);
        assertThat(done.partial().stopReason()).isEqualTo(StopReason.STOP);
        assertThat(done.partial().content()).containsExactly(
            new ContentBlock.TextContent("persisted final"));
        assertThat(done.partial().usage().usage().totalTokens()).isPositive();
        assertThat(done.partial().api()).isEqualTo("faux-api");
        assertThat(done.partial().provider()).isEqualTo("faux");
        assertThat(done.partial().model()).isEqualTo("faux-1");
    }

    @Test
    void withoutTheDeferredOptionTheResponseStreamsNormally() {
        var faux = fauxWith(FauxDeferredOptions.defaults());
        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("plain"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, ApiOptions.defaults());

        var events = drain(api, request(faux), ApiOptions.defaults());

        assertThat(events.get(events.size() - 1).partial().stopReason())
            .isEqualTo(StopReason.STOP);
        assertThat(textOf(events.get(events.size() - 1))).isEqualTo("plain");
    }
}
