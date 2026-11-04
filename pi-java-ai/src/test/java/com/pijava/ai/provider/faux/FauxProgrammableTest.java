package com.pijava.ai.provider.faux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/20 §3：可编程核心（模型、身份、队列、工厂、用量整合）。
 */
class FauxProgrammableTest {

    private static final ApiOptions OPTIONS = ApiOptions.defaults();
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    private static FauxProgrammable defaults() {
        return FauxProvider.programmable(FauxProviderOptions.defaults());
    }

    private static StreamRequest request(FauxProgrammable faux, Message... messages) {
        var list = messages.length == 0
            ? List.<Message>of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hello")), NOW))
            : List.of(messages);
        return new StreamRequest(faux.getModel(), null, list, List.of(), -1, -1);
    }

    private static List<StreamEvent> drain(ChatApi api, StreamRequest request) {
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request, OPTIONS)) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    private static StreamEvent terminal(List<StreamEvent> events) {
        return events.get(events.size() - 1);
    }

    private static AssistantText textOf(StreamEvent event) {
        return new AssistantText(event.partial().content());
    }

    private record AssistantText(List<ContentBlock> content) {
        String joined() {
            return content.stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(ContentBlock.TextContent.class::cast)
                .map(ContentBlock.TextContent::text)
                .reduce("", String::concat);
        }
    }

    @Test
    void defaultsExposeTheDefaultModelAndProviderName() {
        var faux = defaults();

        assertThat(faux.provider().name()).isEqualTo("faux");
        assertThat(faux.provider().supportedApis()).contains(ChatApi.class);
        assertThat(faux.models()).hasSize(1);
        assertThat(faux.getModel().id()).isEqualTo(ModelId.of("faux", "faux-1"));
        assertThat(faux.getModel().displayName()).isEqualTo("Faux Model");
        assertThat(faux.getModel().maxInputTokens()).isEqualTo(128000);
        assertThat(faux.getModel().maxOutputTokens()).isEqualTo(16384);
        assertThat(faux.getModel().capabilities())
            .contains(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT)
            .doesNotContain(ModelCapability.THINKING);
        assertThat(faux.getModel("faux-1")).contains(faux.getModel());
        assertThat(faux.getModel("missing")).isEmpty();
        assertThat(faux.api()).startsWith("faux:");
    }

    @Test
    void customModelDefinitionsMapPiFields() {
        var faux = FauxProvider.programmable(new FauxProviderOptions(
            "custom-api", "custom",
            List.of(new FauxModelDefinition("m1", "M One", true, List.of("text"),
                new PricingInfo(1, 2, 3, 4, List.of()), 5000, 1000, null)),
            null, null, null));

        var model = faux.getModel();
        assertThat(model.id()).isEqualTo(ModelId.of("custom", "m1"));
        assertThat(model.api()).isEqualTo("custom-api");
        assertThat(model.displayName()).isEqualTo("M One");
        assertThat(model.capabilities())
            .contains(ModelCapability.TEXT, ModelCapability.THINKING)
            .doesNotContain(ModelCapability.IMAGE_INPUT);
        assertThat(model.maxInputTokens()).isEqualTo(5000);
        assertThat(model.maxOutputTokens()).isEqualTo(1000);
        assertThat(model.pricing().inputPrice()).isEqualTo(1.0);
        assertThat(faux.provider().builtinModels().listModels()).containsExactly(model);
    }

    @Test
    void queuedResponsesAreConsumedInOrderThenTheQueueErrors() {
        var faux = defaults();
        faux.setResponses(List.of(
            FauxResponseStep.of(FauxMessages.text("one")),
            FauxResponseStep.of(FauxMessages.text("two"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, OPTIONS);

        var first = drain(api, request(faux));
        var second = drain(api, request(faux));
        var third = drain(api, request(faux));

        assertThat(textOf(terminal(first)).joined()).isEqualTo("one");
        assertThat(textOf(terminal(second)).joined()).isEqualTo("two");
        assertThat(terminal(third)).isInstanceOf(StreamEvent.StreamError.class);
        assertThat(terminal(third).partial().errorMessage())
            .isEqualTo("No more faux responses queued");
        assertThat(terminal(third).partial().stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(faux.state().callCount()).isEqualTo(3);
    }

    @Test
    void setResponsesReplacesAndAppendResponsesAppends() {
        var faux = defaults();
        faux.setResponses(List.of(
            FauxResponseStep.of(FauxMessages.text("a")),
            FauxResponseStep.of(FauxMessages.text("b"))));
        assertThat(faux.pendingResponseCount()).isEqualTo(2);

        faux.appendResponses(List.of(FauxResponseStep.of(FauxMessages.text("c"))));
        assertThat(faux.pendingResponseCount()).isEqualTo(3);

        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("d"))));
        assertThat(faux.pendingResponseCount()).isEqualTo(1);
    }

    @Test
    void identityIsRewrittenFromTheCoreOptions() {
        var faux = FauxProvider.programmable(new FauxProviderOptions(
            "custom-api", "custom", List.of(FauxModelDefinition.of("m1"), FauxModelDefinition.of("m2")),
            null, null, null));
        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("hi"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, OPTIONS);

        var events = drain(api, request(faux));

        assertThat(events).allSatisfy(event -> {
            if (event.partial() != null) {
                assertThat(event.partial().api()).isEqualTo("custom-api");
                assertThat(event.partial().provider()).isEqualTo("custom");
                assertThat(event.partial().model()).isEqualTo("m1");
            }
        });
    }

    @Test
    void usageIsEstimatedOnTheTerminalMessage() {
        var faux = defaults();
        faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("bye"))));
        ChatApi api = faux.provider().createApi(ChatApi.class, OPTIONS);

        var events = drain(api, request(faux,
            new Message.SystemMessage("sys", NOW, Map.of(), List.of(), List.of()),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")), NOW)));

        var done = (StreamEvent.StreamDone) terminal(events);
        assertThat(done.partial().usage().inputTokens()).isEqualTo(6);
        assertThat(done.partial().usage().outputTokens()).isEqualTo(1);
        assertThat(done.partial().usage().usage().totalTokens()).isEqualTo(7);
        assertThat(done.partial().usage().usage().cacheRead()).isZero();
    }

    @Test
    void factoriesSeeTheContextStateAndModel() {
        var faux = defaults();
        var seen = new java.util.concurrent.atomic.AtomicReference<TranscriptContext>();
        var seenModel = new java.util.concurrent.atomic.AtomicReference<ModelInfo>();
        faux.setResponses(List.of(FauxResponseStep.of((context, options, state, model) -> {
            seen.set(context);
            seenModel.set(model);
            assertThat(state.callCount()).isEqualTo(1);
            return FauxMessages.text("from factory");
        })));
        ChatApi api = faux.provider().createApi(ChatApi.class, OPTIONS);

        var events = drain(api, request(faux));

        assertThat(textOf(terminal(events)).joined()).isEqualTo("from factory");
        assertThat(seen.get().messages()).hasSize(1);
        assertThat(seenModel.get()).isEqualTo(faux.getModel());
    }

    @Test
    void aThrowingFactoryBecomesAnInStreamError() {
        var faux = defaults();
        faux.setResponses(List.of(FauxResponseStep.of((context, options, state, model) -> {
            throw new IllegalStateException("factory boom");
        })));
        ChatApi api = faux.provider().createApi(ChatApi.class, OPTIONS);

        var events = drain(api, request(faux));

        assertThat(terminal(events)).isInstanceOf(StreamEvent.StreamError.class);
        assertThat(terminal(events).partial().errorMessage()).isEqualTo("factory boom");
        assertThat(terminal(events).partial().stopReason()).isEqualTo(StopReason.ERROR);
    }

    @Test
    void queueExhaustionErrorsCarryEstimatedUsageToo() {
        var faux = defaults();
        ChatApi api = faux.provider().createApi(ChatApi.class, OPTIONS);

        var events = drain(api, request(faux,
            new Message.SystemMessage("sys", NOW, Map.of(), List.of(), List.of()),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")), NOW)));

        var error = (StreamEvent.StreamError) terminal(events);
        assertThat(error.partial().usage().inputTokens()).isEqualTo(6);
        assertThat(error.partial().usage().outputTokens()).isZero();
    }

    @Test
    void providerNameAndModelsAreExposedThroughTheCatalog() {
        var faux = defaults();

        assertThat(faux.provider().getModels()).containsExactly(faux.getModel());
        assertThat(faux.provider().builtinModels().find(faux.getModel().id()))
            .contains(faux.getModel());
        assertThat(faux.provider().builtinModels().search("Faux"))
            .containsExactly(faux.getModel());
    }

    @Test
    void theProgrammableProviderDoesNotDisturbTheReplayEntryPoints() {
        var replay = FauxProvider.text("Hello, world!");
        ChatApi api = replay.createApi(ChatApi.class, OPTIONS);

        var events = drain(api, request(defaults()));

        assertThat(events).hasSize(5);
        assertThat(events.get(4)).isInstanceOf(StreamEvent.StreamDone.class);
    }
}
