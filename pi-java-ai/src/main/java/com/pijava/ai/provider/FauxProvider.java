package com.pijava.ai.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;

/**
 * A programmable fake provider for testing.
 *
 * <p>Allows tests to preset a list of {@link StreamEvent} values that will
 * be replayed in order when any chat API method is called. Supports text
 * responses, tool calls, errors, and configurable inter-event delay.</p>
 */
public final class FauxProvider implements Provider {

    private final String name;
    private final List<StreamEvent> events;
    private final long delayMs;

    public FauxProvider(String name, List<StreamEvent> events, long delayMs) {
        this.name = name;
        this.events = List.copyOf(events);
        this.delayMs = delayMs;
    }

    /** Convenience: create a FauxProvider that returns the given text. */
    public static FauxProvider text(String text) {
        return new FauxProvider("faux", List.of(
                new StreamEvent.TextDelta(text, StreamEvent.TextDelta.TEXT),
                new StreamEvent.StreamDone("stop", null)
        ), 0);
    }

    /** Convenience: create a FauxProvider that simulates a tool call. */
    public static FauxProvider toolCall(String toolName, java.util.Map<String, Object> args) {
        var callId = "faux_call_1";
        return new FauxProvider("faux-tool", List.of(
                new StreamEvent.ToolCallStart(callId, toolName),
                new StreamEvent.ToolCallEnd(callId, toolName, args),
                new StreamEvent.TextDelta("Tool called: " + toolName, StreamEvent.TextDelta.TEXT),
                new StreamEvent.StreamDone("tool_calls", null)
        ), 0);
    }

    /** Convenience: create a FauxProvider that returns an error. */
    public static FauxProvider error(String message) {
        return new FauxProvider("faux-error", List.of(
                new StreamEvent.StreamError(new RuntimeException(message))
        ), 0);
    }

    @Override
    public String name() { return name; }

    @Override
    public String displayName() { return "Faux (" + name + ")"; }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ChatApi.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        if (apiType.equals(ChatApi.class)) {
            return (T) new FauxChatApi(events, delayMs);
        }
        throw new IllegalArgumentException("Unsupported API type: " + apiType);
    }

    @Override
    public ModelCatalog builtinModels() {
        return ModelCatalog.empty();
    }

    // ── FauxChatApi ───────────────────────────────────────────

    private static final class FauxChatApi implements ChatApi {

        private final List<StreamEvent> events;
        private final long delayMs;

        FauxChatApi(List<StreamEvent> events, long delayMs) {
            this.events = events;
            this.delayMs = delayMs;
        }

        @Override
        public java.util.concurrent.Flow.Publisher<StreamEvent> stream(
                StreamRequest request, ApiOptions options) {
            var publisher = new java.util.concurrent.SubmissionPublisher<StreamEvent>();
            Thread.startVirtualThread(() -> {
                try {
                    for (var event : events) {
                        if (delayMs > 0) Thread.sleep(delayMs);
                        publisher.submit(event);
                    }
                    publisher.close();
                } catch (Exception e) {
                    publisher.closeExceptionally(e);
                }
            });
            return publisher;
        }

        @Override
        public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
            return StreamIterator.from(events);
        }

        @Override
        public Message send(StreamRequest request, ApiOptions options) {
            var blocks = new ArrayList<ContentBlock>();
            for (var event : events) {
                if (event instanceof StreamEvent.TextDelta(var text, var type)) {
                    blocks.add(new ContentBlock.TextContent(text));
                }
            }
            return new Message.AssistantMessage(blocks);
        }
    }
}
