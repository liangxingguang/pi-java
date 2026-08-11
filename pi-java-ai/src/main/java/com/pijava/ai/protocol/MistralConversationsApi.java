package com.pijava.ai.protocol;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.http.PiHttpClient;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.stream.ToolCallBuilder;

/**
 * Mistral Chat Completions API adapter using raw HTTP + SSE.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots
 * via {@link StreamPartialBuilder}. Mistral has no official Java SDK; uses
 * {@link PiHttpClient} for JSON requests and SSE parsing.</p>
 */
public final class MistralConversationsApi extends AbstractChatApi {

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

    // ── Internals ─────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked") // SSE response JSON parsing with generic Map types
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        try {
            String jsonBody = buildRequestBody(request);
            var headers = Map.of(
                    "Authorization", "Bearer " + apiKey,
                    "Accept", "text/event-stream");

            publisher.submit(builder.emitStart());

            Iterator<PiHttpClient.ServerSentEvent> sseEvents =
                    http.postSse(baseUrl + "/chat/completions", jsonBody, headers);

            var toolCallBuilders = new HashMap<String, ToolCallBuilder>();
            var textStarted = new boolean[]{false};
            String finishReason = "stop";

            while (sseEvents.hasNext()) {
                var sse = sseEvents.next();
                if ("[DONE]".equals(sse.data())) {
                    if (textStarted[0]) publisher.submit(builder.emitTextEnd());
                    publisher.submit(builder.emitDone(finishReason));
                    return;
                }
                finishReason = processSseData(sse.data(), publisher, builder,
                        toolCallBuilders, textStarted);
            }
            if (textStarted[0]) publisher.submit(builder.emitTextEnd());
            publisher.submit(builder.emitDone(finishReason));
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
    }

    @SuppressWarnings("unchecked") // SSE response JSON parsing with generic Map types
    private String processSseData(String data,
                                  SubmissionPublisher<StreamEvent> publisher,
                                  StreamPartialBuilder builder,
                                  Map<String, ToolCallBuilder> toolBuilders,
                                  boolean[] textStarted) {
        String finishReason = "stop";
        try {
            var json = MAPPER.readValue(data, Map.class);
            var choices = (List<Map<String, Object>>) json.get("choices");
            if (choices == null || choices.isEmpty()) return finishReason;

            var choice = choices.get(0);
            var delta = (Map<String, Object>) choice.get("delta");
            if (delta == null) return finishReason;

            // Text delta
            var content = (String) delta.get("content");
            if (content != null && !content.isEmpty()) {
                if (!textStarted[0]) {
                    publisher.submit(builder.emitTextStart());
                    textStarted[0] = true;
                }
                publisher.submit(builder.emitTextDelta(content));
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

                    var toolBuilder = toolBuilders.computeIfAbsent(index,
                            k -> new ToolCallBuilder());

                    if (tcId != null && name != null && !toolBuilder.isStarted()) {
                        toolBuilder.start(tcId, name);
                        publisher.submit(builder.emitToolCallStart());
                    }
                    if (args != null) {
                        toolBuilder.append(args);
                        publisher.submit(builder.emitToolCallDelta(
                                toolBuilder.id(), args));
                    }
                }
            }

            // Finish reason — emit ToolCallEnd when tool_use, before StreamDone
            var reason = (String) choice.get("finish_reason");
            if (reason != null && !reason.isEmpty()) {
                var normalized = "tool_calls".equals(reason) ? "tool_use" : reason;
                finishReason = normalized;
                if ("tool_use".equals(normalized)) {
                    for (var tb : toolBuilders.values()) {
                        if (tb.isComplete()) {
                            publisher.submit(builder.emitToolCallEnd(
                                    tb.id(), tb.name()));
                        }
                    }
                }
            }

            // Usage
            var usage = (Map<String, Object>) json.get("usage");
            if (usage != null) {
                long promptTokens = ((Number) usage.getOrDefault("prompt_tokens", 0)).longValue();
                long completionTokens = ((Number) usage.getOrDefault("completion_tokens", 0)).longValue();
                publisher.submit(builder.emitUsage(promptTokens, completionTokens));
            }

        } catch (JsonProcessingException e) {
            // Skip unparseable data lines
        }
        return finishReason;
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
