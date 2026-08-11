package com.pijava.ai.provider;

import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FauxProvider} — the programmable fake provider for testing.
 */
class FauxProviderTest {

    private static final ApiOptions OPTIONS = ApiOptions.defaults();
    private static final StreamRequest REQUEST = new StreamRequest(
            ModelId.of("faux", "test-model"),
            java.util.List.of(new Message.UserMessage(
                    java.util.List.of(new ContentBlock.TextContent("hello")))),
            java.util.List.of(), -1, -1, java.util.Map.of());

    @Test
    void textModeShouldReturnTextAndDone() {
        var provider = FauxProvider.text("Hello, world!");
        ChatApi api = provider.createApi(ChatApi.class, OPTIONS);

        var events = new java.util.ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(REQUEST, OPTIONS)) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.TextDelta.class);
        assertThat(((StreamEvent.TextDelta) events.get(0)).text()).isEqualTo("Hello, world!");
        assertThat(events.get(1)).isInstanceOf(StreamEvent.StreamDone.class);
    }

    @Test
    void toolCallModeShouldReturnToolEvents() {
        Map<String, Object> args = Map.of("path", "/src/main.java");
        var provider = FauxProvider.toolCall("read", args);
        ChatApi api = provider.createApi(ChatApi.class, OPTIONS);

        var events = new java.util.ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(REQUEST, OPTIONS)) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }

        assertThat(events).hasSize(4);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.ToolCallStart.class);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.ToolCallEnd.class);
        assertThat(events.get(2)).isInstanceOf(StreamEvent.TextDelta.class);
        assertThat(events.get(3)).isInstanceOf(StreamEvent.StreamDone.class);

        var start = (StreamEvent.ToolCallStart) events.get(0);
        assertThat(start.name()).isEqualTo("read");
        var end = (StreamEvent.ToolCallEnd) events.get(1);
        assertThat(end.name()).isEqualTo("read");
        assertThat(end.arguments()).containsEntry("path", "/src/main.java");
    }

    @Test
    void errorModeShouldReturnStreamError() {
        var provider = FauxProvider.error("Connection refused");
        ChatApi api = provider.createApi(ChatApi.class, OPTIONS);

        var events = new java.util.ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(REQUEST, OPTIONS)) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.StreamError.class);
        var error = (StreamEvent.StreamError) events.get(0);
        assertThat(error.error().getMessage()).isEqualTo("Connection refused");
    }

    @Test
    void sendShouldCollectTextBlocks() {
        var provider = FauxProvider.text("Aggregated response");
        ChatApi api = provider.createApi(ChatApi.class, OPTIONS);

        var result = api.send(REQUEST, OPTIONS);

        assertThat(result).isInstanceOf(Message.AssistantMessage.class);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0)).isInstanceOf(ContentBlock.TextContent.class);
        assertThat(((ContentBlock.TextContent) result.content().get(0)).text())
                .isEqualTo("Aggregated response");
    }

    @Test
    void nameShouldBeConfigurable() {
        var provider = new FauxProvider("my-test", java.util.List.of(), 0);

        assertThat(provider.name()).isEqualTo("my-test");
        assertThat(provider.displayName()).contains("my-test");
    }

    @Test
    void shouldSupportChatApi() {
        var provider = FauxProvider.text("test");
        assertThat(provider.supportedApis()).contains(ChatApi.class);
    }

    @Test
    void builtinModelsShouldBeEmpty() {
        var provider = FauxProvider.text("test");
        assertThat(provider.builtinModels().listModels()).isEmpty();
    }
}
