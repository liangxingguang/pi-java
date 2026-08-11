package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.http.PiHttpClient;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamEvent.StreamDone;
import com.pijava.ai.stream.StreamEvent.StreamError;
import com.pijava.ai.stream.StreamEvent.TextDelta;
import com.pijava.ai.stream.StreamEvent.ToolCallDelta;
import com.pijava.ai.stream.StreamEvent.ToolCallEnd;
import com.pijava.ai.stream.StreamEvent.ToolCallStart;
import com.pijava.ai.stream.StreamEvent.UsageInfo;
import com.pijava.ai.stream.ToolCallBuilder;

/**
 * Mistral Chat Completions API adapter using raw HTTP + SSE.
 *
 * <p>Mistral has no official Java SDK. This adapter uses {@link PiHttpClient}
 * for JSON request construction and SSE response parsing. The tool-call delta
 * aggregation reuses the shared {@link ToolCallBuilder}.</p>
 */
public final class MistralConversationsApi implements ChatApi {

    private static final String DEFAULT_BASE_URL = "https://api.mistral.ai/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PiHttpClient http;
    private final String apiKey;
    private final String baseUrl;

    public MistralConversationsApi(ApiOptions options) {
        this.apiKey = resolveApiKey(options);
        this.baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
                ? options.baseUrl() : DEFAULT_BASE_URL;
        this.http = PiHttpClient.builder()
                .userAgent("pi-java/dev")
                .build();
    }

    @Override
    public Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions options) {
        var publisher = new SubmissionPublisher<StreamEvent>();
        Thread.startVirtualThread(() -> {
            try {
                streamInternal(request, publisher);
                publisher.close();
            } catch (Exception e) {
                publisher.closeExceptionally(e);
            }
        });
        return publisher;
    }

    @Override
    public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        stream(request, options).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription s) {
                this.subscription = s; s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(StreamEvent e) { queue.offer(e); }
            @Override public void onError(Throwable t) { queue.offer(new StreamError(t)); }
            @Override public void onComplete() {}
        });
        return new com.pijava.ai.protocol.QueueStreamIterator(queue);
    }

    @Override
    public com.pijava.ai.message.Message send(StreamRequest request, ApiOptions options) {
        var blocks = new ArrayList<ContentBlock>();
        try (var iter = streamBlocking(request, options)) {
            while (iter.hasNext()) {
                var event = iter.next();
                if (event instanceof TextDelta td) {
                    blocks.add(new ContentBlock.TextContent(td.text()));
                }
            }
        } catch (Exception e) {
            throw new com.pijava.ai.http.PiHttpException(0, "Request failed", e);
        }
        return new Message.AssistantMessage(blocks);
    }

    // ── Internals ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void streamInternal(StreamRequest request,
                                 SubmissionPublisher<StreamEvent> publisher) {
        try {
            String jsonBody = buildRequestBody(request);
            var headers = Map.of(
                    "Authorization", "Bearer " + apiKey,
                    "Accept", "text/event-stream");

            Iterator<PiHttpClient.ServerSentEvent> sseEvents =
                    http.postSse(baseUrl + "/chat/completions", jsonBody, headers);

            var toolCallBuilders = new HashMap<String, ToolCallBuilder>();

            while (sseEvents.hasNext()) {
                var sse = sseEvents.next();
                if ("[DONE]".equals(sse.data())) {
                    for (var builder : toolCallBuilders.values()) {
                        if (builder.isComplete()) {
                            publisher.submit(builder.toEnd());
                        }
                    }
                    publisher.submit(new StreamDone("stop", null));
                    return;
                }
                processSseData(sse.data(), publisher, toolCallBuilders);
            }
        } catch (Exception e) {
            publisher.submit(new StreamError(e));
        }
    }

    @SuppressWarnings("unchecked")
    private void processSseData(String data,
                                 SubmissionPublisher<StreamEvent> publisher,
                                 Map<String, ToolCallBuilder> builders) {
        try {
            var json = MAPPER.readValue(data, Map.class);
            var choices = (List<Map<String, Object>>) json.get("choices");
            if (choices == null || choices.isEmpty()) return;

            var choice = choices.get(0);
            var delta = (Map<String, Object>) choice.get("delta");
            if (delta == null) return;

            // Text delta
            var content = (String) delta.get("content");
            if (content != null && !content.isEmpty()) {
                publisher.submit(new TextDelta(content, TextDelta.TEXT));
            }

            // Tool call delta
            var toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolCalls != null) {
                for (var tc : toolCalls) {
                    var index = String.valueOf(tc.getOrDefault("index", "0"));
                    var tcId = (String) tc.get("id");
                    var function = (Map<String, Object>) tc.get("function");
                    if (function == null) continue;

                    var name = (String) function.get("name");
                    var args = (String) function.get("arguments");

                    var builder = builders.computeIfAbsent(index,
                            k -> new ToolCallBuilder());

                    if (tcId != null && name != null && !builder.isStarted()) {
                        builder.start(tcId, name);
                        publisher.submit(new ToolCallStart(tcId, name));
                    }
                    if (args != null) {
                        builder.append(args);
                        publisher.submit(new ToolCallDelta(builder.id(), args));
                    }
                }
            }

            // Finish reason
            var finishReason = (String) choice.get("finish_reason");
            if ("stop".equals(finishReason)) {
                publisher.submit(new StreamDone("stop", null));
            } else if ("tool_calls".equals(finishReason)) {
                for (var builder : builders.values()) {
                    if (builder.isComplete()) {
                        publisher.submit(builder.toEnd());
                    }
                }
            }

            // Usage
            var usage = (Map<String, Object>) json.get("usage");
            if (usage != null) {
                long promptTokens = ((Number) usage.getOrDefault("prompt_tokens", 0)).longValue();
                long completionTokens = ((Number) usage.getOrDefault("completion_tokens", 0)).longValue();
                publisher.submit(new UsageInfo(promptTokens, completionTokens));
            }

        } catch (JsonProcessingException e) {
            // Skip unparseable data lines
        }
    }

    @SuppressWarnings("unchecked")
    private String buildRequestBody(StreamRequest request) throws JsonProcessingException {
        var body = new HashMap<String, Object>();
        body.put("model", request.model().modelName());
        body.put("stream", true);
        body.put("messages", toMistralMessages(request.messages()));

        if (!request.tools().isEmpty()) {
            body.put("tools", toMistralTools(request.tools()));
        }
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.temperature() >= 0) {
            body.put("temperature", request.temperature());
        }

        return MAPPER.writeValueAsString(body);
    }

    private List<Map<String, Object>> toMistralMessages(List<Message> messages) {
        return messages.stream().<Map<String, Object>>map(msg -> {
            var m = new HashMap<String, Object>();
            switch (msg) {
                case Message.SystemMessage(var content) -> {
                    m.put("role", "system");
                    m.put("content", extractText(content));
                }
                case Message.UserMessage(var content) -> {
                    m.put("role", "user");
                    m.put("content", extractText(content));
                }
                case Message.AssistantMessage(var content) -> {
                    m.put("role", "assistant");
                    m.put("content", extractText(content));
                }
            }
            return m;
        }).toList();
    }

    private List<Map<String, Object>> toMistralTools(List<ToolDefinition> definitions) {
        return definitions.stream().<Map<String, Object>>map(def -> {
            var tool = new HashMap<String, Object>();
            tool.put("type", "function");
            var function = new HashMap<String, Object>();
            function.put("name", def.name());
            function.put("description", def.description());
            function.put("parameters", def.inputSchema());
            tool.put("function", function);
            return tool;
        }).toList();
    }

    private String extractText(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(c -> c instanceof ContentBlock.TextContent)
                .map(c -> ((ContentBlock.TextContent) c).text())
                .reduce("", String::concat);
    }

    private static String resolveApiKey(ApiOptions options) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        var env = System.getenv("MISTRAL_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException(
                "No Mistral API key found. Set MISTRAL_API_KEY or pass apiKey in ApiOptions.");
    }

}
