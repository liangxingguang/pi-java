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
import com.pijava.ai.message.AssistantMessage;
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

    /**
     * Convenience: create a FauxProvider that returns the given text.
     * Produces a full event sequence: Start → TextStart → TextDelta → TextEnd → StreamDone.
     */
    public static FauxProvider text(String text) {
        var msg = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason("stop");
        var partial0 = AssistantMessage.empty();
        var partial1 = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("")));
        return new FauxProvider("faux", List.of(
                new StreamEvent.Start(partial0),
                new StreamEvent.TextStart(0, partial1),
                new StreamEvent.TextDelta(0, text, msg.withStopReason(null)),
                new StreamEvent.TextEnd(0, text, msg.withStopReason(null)),
                new StreamEvent.StreamDone("stop", null, msg)
        ), 0);
    }

    /**
     * Convenience: create a FauxProvider that simulates a tool call.
     * Produces: Start → ToolCallStart → ToolCallDelta → ToolCallEnd → StreamDone.
     */
    public static FauxProvider toolCall(String toolName, java.util.Map<String, Object> args) {
        var callId = "faux_call_1";
        var block = new ContentBlock.ToolUseContent(callId, toolName, args);
        var finalMsg = AssistantMessage.empty()
                .withContent(List.of(block))
                .withStopReason("tool_use");
        return new FauxProvider("faux-tool", List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.ToolCallStart(0, AssistantMessage.empty()
                        .withContent(List.of(new ContentBlock.ToolUseContent("", "", java.util.Map.of())))),
                new StreamEvent.ToolCallEnd(0, callId, toolName, args, finalMsg.withStopReason(null)),
                new StreamEvent.StreamDone("tool_use", null, finalMsg)
        ), 0);
    }

    /**
     * Convenience: create a FauxProvider that returns an error.
     * Produces: Start → StreamError.
     */
    public static FauxProvider error(String message) {
        return new FauxProvider("faux-error", List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.StreamError("error",
                        new RuntimeException(message), AssistantMessage.empty())
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
                if (event instanceof StreamEvent.StreamDone done) {
                    return new Message.AssistantMessage(done.partial().content());
                }
            }
            return new Message.AssistantMessage(blocks);
        }
    }
}
