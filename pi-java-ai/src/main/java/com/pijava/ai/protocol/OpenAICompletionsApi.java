package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * OpenAI Chat Completions adapter using the official {@code openai-java} SDK.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots
 * via {@link StreamPartialBuilder}.</p>
 */
public class OpenAICompletionsApi extends AbstractChatApi {

    protected final OpenAIClient client;
    protected final String apiKey;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Create an adapter for the given options.
     *
     * @param options API options (apiKey or {@code OPENAI_API_KEY} required)
     */
    public OpenAICompletionsApi(ApiOptions options) {
        this(options, "OPENAI_API_KEY");
    }

    /**
     * Create an adapter for the given options, resolving the API key from an env var.
     *
     * @param options     API options (apiKey or env var required)
     * @param apiKeyEnvVar the environment variable holding the API key
     */
    public OpenAICompletionsApi(ApiOptions options, String apiKeyEnvVar) {
        this.apiKey = resolveApiKey(options, apiKeyEnvVar);
        var baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
                ? options.baseUrl() : "https://api.openai.com/v1";
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey).baseUrl(baseUrl).build();
    }

    @Override
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        boolean textStarted = false;
        var toolCall = new ToolCallAccumulator();
        try {
            var params = buildParams(request);
            publisher.submit(builder.emitStart());

            try (var streamResponse = client.chat().completions().createStreaming(params)) {

                for (var chunk : streamResponse.stream().toList()) {
                    if (chunk.choices().isEmpty()) {
                        if (chunk.usage().isPresent()) {
                            var u = chunk.usage().get();
                            publisher.submit(builder.emitUsage(u.promptTokens(), u.completionTokens()));
                        }
                        continue;
                    }
                    var choice = chunk.choices().get(0);
                    var delta = choice.delta();

                    // Text content
                    if (delta.content().isPresent()) {
                        var text = delta.content().get();
                        if (!text.isEmpty()) {
                            if (!textStarted) {
                                publisher.submit(builder.emitTextStart());
                                textStarted = true;
                            }
                            publisher.submit(builder.emitTextDelta(text));
                        }
                    }

                    // Tool calls — accumulate deltas; emit ToolCallEnd at finish.
                    // id / name / arguments may arrive in separate chunks
                    // (DeepSeek etc.); start on the first chunk whatever it
                    // contains so the call is never dropped.
                    if (delta.toolCalls().isPresent()) {
                        for (var tc : delta.toolCalls().get()) {
                            var fn = tc.function();
                            toolCall.update(
                                tc.id().orElse(null),
                                fn.flatMap(f -> f.name()).orElse(null),
                                fn.flatMap(f -> f.arguments()).orElse(null),
                                publisher::submit, builder);
                        }
                    }

                    // Usage
                    if (chunk.usage().isPresent()) {
                        var u = chunk.usage().get();
                        publisher.submit(builder.emitUsage(u.promptTokens(), u.completionTokens()));
                    }
                }
            }
            // Emit block-end events before StreamDone
            if (textStarted) publisher.submit(builder.emitTextEnd());
            toolCall.finish(publisher::submit, builder);
            String reason = toolCall.started() ? "tool_use" : "stop";
            publisher.submit(builder.emitDone(reason));
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
    }

    static ChatCompletionCreateParams buildParams(StreamRequest request) {
        var builder = ChatCompletionCreateParams.builder()
                .model(request.model().modelName());

        for (var msg : request.messages()) {
            if (msg instanceof Message.SystemMessage) {
                var text = extractText(msg.content());
                if (!text.isEmpty()) builder.addSystemMessage(text);
            } else if (msg instanceof Message.UserMessage) {
                var text = extractText(msg.content());
                if (!text.isEmpty()) builder.addUserMessage(text);
            } else if (msg instanceof Message.AssistantMessage assistant) {
                addAssistantMessage(builder, assistant, request.model().provider());
            } else if (msg instanceof Message.ToolResultMessage tool) {
                // Tool results must be sent back to the model, otherwise it
                // cannot see the outcome and keeps repeating the same tool
                // call (observed as duplicated write blocks in the TUI).
                builder.addMessage(ChatCompletionToolMessageParam.builder()
                    .toolCallId(tool.toolUseId())
                    .content(extractText(tool.content()))
                    .build());
            }
        }

        // Pass tools so the model emits structured tool_calls instead of
        // writing fake XML tool invocations into the text stream (which also
        // avoids garbled interleaving in the rendered bubble).
        for (var td : request.tools()) {
            builder.addTool(ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder()
                    .type(JsonValue.from("function"))
                    .function(FunctionDefinition.builder()
                        .name(td.name())
                        .description(td.description())
                        .parameters(FunctionParameters.builder()
                            .putAllAdditionalProperties(toJsonValues(td.inputSchema()))
                            .build())
                        .build())
                    .build()));
        }
        // Ask for usage in the stream so the token counter/status bar updates.
        builder.streamOptions(ChatCompletionStreamOptions.builder()
            .includeUsage(true)
            .build());

        if (request.maxTokens() > 0) builder.maxCompletionTokens(request.maxTokens());
        if (request.temperature() >= 0) builder.temperature(request.temperature());

        return builder.build();
    }

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> schema) {
        var out = new LinkedHashMap<String, JsonValue>();
        schema.forEach((key, value) -> out.put(key, JsonValue.from(value)));
        return out;
    }

    /** Serializes an assistant message including its tool calls. */
    private static void addAssistantMessage(
            ChatCompletionCreateParams.Builder builder,
            Message.AssistantMessage assistant, String provider) {
        var text = new StringBuilder();
        var reasoning = new StringBuilder();
        var toolCalls = new ArrayList<ChatCompletionMessageToolCall>();
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                text.append(tc.text());
            } else if (block instanceof ContentBlock.ThinkingContent thinking) {
                reasoning.append(thinking.text());
            } else if (block instanceof ContentBlock.ToolUseContent toolUse) {
                toolCalls.add(ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                        .id(toolUse.id())
                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(toolUse.name())
                            .arguments(toArgumentsJson(toolUse.arguments()))
                            .build())
                        .build()));
            }
        }
        var ab = ChatCompletionAssistantMessageParam.builder();
        if (!text.isEmpty()) {
            ab.content(text.toString());
        }
        if (!toolCalls.isEmpty()) {
            ab.toolCalls(toolCalls);
        }
        // DeepSeek thinking mode requires reasoning_content on assistant
        // history messages; without it the API rejects the turn with 400.
        // This field is DeepSeek-specific: only round-trip it for providers
        // that demand it, so OpenAI/Mistral/vLLM never receive an unknown
        // parameter on the same OpenAI-compatible path.
        if (!reasoning.isEmpty() && "deepseek".equalsIgnoreCase(provider)) {
            ab.putAdditionalProperty("reasoning_content",
                com.openai.core.JsonValue.from(reasoning.toString()));
        }
        if (!text.isEmpty() || !toolCalls.isEmpty() || !reasoning.isEmpty()) {
            builder.addMessage(ab.build());
        }
    }

    private static String toArgumentsJson(Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String extractText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) sb.append(tc.text());
        }
        return sb.toString();
    }
}
