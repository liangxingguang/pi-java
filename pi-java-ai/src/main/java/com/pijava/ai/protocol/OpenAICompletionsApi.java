package com.pijava.ai.protocol;

import java.util.List;
import java.util.concurrent.SubmissionPublisher;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

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

    public OpenAICompletionsApi(ApiOptions options) {
        this(options, "OPENAI_API_KEY");
    }

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
        boolean toolStarted = false;
        var pendingToolId = new String[]{""};
        var pendingToolName = new String[]{""};
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

                    // Tool calls — accumulate deltas; emit ToolCallEnd at finish
                    if (delta.toolCalls().isPresent()) {
                        for (var tc : delta.toolCalls().get()) {
                            // First appearance: has id + function name
                            if (tc.id().isPresent() && tc.function().isPresent()) {
                                var func = tc.function().get();
                                pendingToolId[0] = tc.id().get();
                                pendingToolName[0] = func.name().orElse("");
                                if (!toolStarted) {
                                    publisher.submit(builder.emitToolCallStart());
                                    toolStarted = true;
                                }
                                if (func.arguments().isPresent()) {
                                    publisher.submit(builder.emitToolCallDelta(
                                            pendingToolId[0], func.arguments().get()));
                                }
                            } else if (tc.function().isPresent()) {
                                // Subsequent chunks: only function.arguments
                                var func = tc.function().get();
                                if (func.arguments().isPresent()) {
                                    publisher.submit(builder.emitToolCallDelta(
                                            pendingToolId[0], func.arguments().get()));
                                }
                            }
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
            if (toolStarted) {
                publisher.submit(builder.emitToolCallEnd(
                        pendingToolId[0], pendingToolName[0]));
            }
            String reason = toolStarted ? "tool_use" : "stop";
            publisher.submit(builder.emitDone(reason));
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
    }

    private ChatCompletionCreateParams buildParams(StreamRequest request) {
        var builder = ChatCompletionCreateParams.builder()
                .model(request.model().modelName());

        for (var msg : request.messages()) {
            var text = extractText(msg.content());
            if (text.isEmpty()) continue;
            if (msg instanceof Message.SystemMessage) {
                builder.addSystemMessage(text);
            } else if (msg instanceof Message.UserMessage) {
                builder.addUserMessage(text);
            } else if (msg instanceof Message.AssistantMessage) {
                builder.addAssistantMessage(text);
            }
        }

        // TODO: add tool support (requires FunctionDefinition/parameters type mapping)
        if (request.maxTokens() > 0) builder.maxCompletionTokens(request.maxTokens());
        if (request.temperature() >= 0) builder.temperature(request.temperature());

        return builder.build();
    }

    private String extractText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) sb.append(tc.text());
        }
        return sb.toString();
    }

    protected static String resolveApiKey(ApiOptions options, String envVar) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) return options.apiKey();
        var env = System.getenv(envVar);
        if (env != null && !env.isBlank()) return env;
        throw new IllegalStateException("No API key. Set " + envVar + " or pass apiKey.");
    }
}
