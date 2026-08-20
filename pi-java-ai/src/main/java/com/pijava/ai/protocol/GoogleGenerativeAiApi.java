package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * Google Gemini API adapter using the official {@code google-genai} SDK.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots.
 * Google returns complete function-call arguments in a single response
 * (no delta aggregation needed).</p>
 */
public final class GoogleGenerativeAiApi extends AbstractChatApi {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final Client client;

    /**
     * Create an adapter for the given options.
     *
     * @param options API options (apiKey or {@code GEMINI_API_KEY} required)
     */
    public GoogleGenerativeAiApi(ApiOptions options) {
        var apiKey = resolveApiKey(options);
        var baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
                ? options.baseUrl() : DEFAULT_BASE_URL;
        this.client = Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().baseUrl(baseUrl).build())
                .build();
    }

    // ── Internals ─────────────────────────────────────────────────

    @Override
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        String finishReason = null;
        try {
            var contents = toGoogleContents(request.messages());
            var config = buildConfig(request);

            publisher.submit(builder.emitStart());

            boolean textStarted = false;
            boolean thinkingStarted = false;
            try (ResponseStream<GenerateContentResponse> stream =
                         client.models.generateContentStream(
                                 request.model().modelName(), contents, config)) {

                for (var response : stream) {
                    // Safety filter check
                    if (response.promptFeedback().isPresent()) {
                        var fb = response.promptFeedback().get();
                        if (fb.blockReason().isPresent()) {
                            publisher.submit(builder.emitError("error",
                                    new IllegalStateException(
                                            "Content blocked by Google safety filter: "
                                                    + fb.blockReason().get())));
                            return;
                        }
                    }

                    // Usage metadata
                    if (response.usageMetadata().isPresent()) {
                        var usage = response.usageMetadata().get();
                        long input = usage.promptTokenCount().orElse(0);
                        long output = usage.candidatesTokenCount().orElse(0)
                                + usage.thoughtsTokenCount().orElse(0);
                        publisher.submit(builder.emitUsage(input, output));
                    }

                    // Process candidates
                    if (response.candidates().isEmpty()) continue;
                    for (var candidate : response.candidates().get()) {
                        if (candidate.content().isEmpty()) continue;
                        var parts = candidate.content().get().parts();
                        if (parts.isEmpty()) continue;

                        for (var part : parts.get()) {
                            // Thinking block — Google's thought() is a boolean flag
                            // indicating the text content represents model thinking
                            if (part.thought().isPresent() && part.thought().get()
                                    && part.text().isPresent()) {
                                String thought = part.text().get();
                                if (!thinkingStarted) {
                                    publisher.submit(builder.emitThinkingStart());
                                    thinkingStarted = true;
                                }
                                publisher.submit(builder.emitThinkingDelta(thought));
                                continue;
                            }

                            // Text
                            if (part.text().isPresent()) {
                                String text = part.text().get();
                                if (!textStarted) {
                                    publisher.submit(builder.emitTextStart());
                                    textStarted = true;
                                }
                                publisher.submit(builder.emitTextDelta(text));
                            }

                            // Function call (Google returns complete args — no delta)
                            if (part.functionCall().isPresent()) {
                                FunctionCall fc = part.functionCall().get();
                                String id = fc.id().orElse(
                                        fc.name().orElse("unknown") + "_"
                                                + System.currentTimeMillis());
                                String name = fc.name().orElse("");
                                Map<String, Object> args = fc.args().orElse(Map.of());

                                publisher.submit(builder.emitToolCallStart());
                                publisher.submit(builder.emitToolCallDelta(id, ""));
                                publisher.submit(builder.emitToolCallEnd(id, name));
                            }
                        }
                    }

                    // Finish reason
                    if (response.finishReason() != null) {
                        finishReason = response.finishReason().toString().toLowerCase();
                    }
                }
            }
            if (thinkingStarted) publisher.submit(builder.emitThinkingEnd());
            if (textStarted) publisher.submit(builder.emitTextEnd());
            publisher.submit(builder.emitDone(finishReason != null ? finishReason : "stop"));
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
    }

    private GenerateContentConfig buildConfig(StreamRequest request) {
        var builder = GenerateContentConfig.builder();

        // System instruction
        var systemText = extractSystemText(request.messages());
        if (!systemText.isEmpty()) {
            builder.systemInstruction(
                    Content.fromParts(Part.fromText(systemText)));
        }

        if (request.maxTokens() > 0) {
            builder.maxOutputTokens(request.maxTokens());
        }
        if (request.temperature() >= 0) {
            builder.temperature((float) request.temperature());
        }

        // Tools
        if (!request.tools().isEmpty()) {
            builder.tools(List.of(Tool.builder()
                    .functionDeclarations(toGoogleFunctions(request.tools()))
                    .build()));
        }

        return builder.build();
    }

    private List<Content> toGoogleContents(List<Message> messages) {
        var contents = new ArrayList<Content>();
        for (var msg : messages) {
            if (msg instanceof Message.SystemMessage) {
                continue; // handled separately as systemInstruction
            }
            var role = msg instanceof Message.UserMessage ? "user" : "model";
            var parts = new ArrayList<Part>();
            for (var block : msg.content()) {
                parts.addAll(toGoogleParts(block));
            }
            if (!parts.isEmpty()) {
                contents.add(Content.builder()
                        .role(role)
                        .parts(parts)
                        .build());
            }
        }
        return contents;
    }

    private List<Part> toGoogleParts(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextContent tc ->
                    List.of(Part.fromText(tc.text()));
            case ContentBlock.ThinkingContent tc ->
                    List.of(); // Gemini has its own thinking protocol; do not echo it as text
            case ContentBlock.ToolUseContent tc -> {
                var fc = FunctionCall.builder()
                        .name(tc.name())
                        .args(tc.arguments());
                if (tc.id() != null && !tc.id().isEmpty()) {
                    fc.id(tc.id());
                }
                yield List.of(Part.builder()
                        .functionCall(fc.build())
                        .build());
            }
            case ContentBlock.ToolResultContent tc -> {
                    // Extract text from content blocks for the function response
                    String text = tc.content().stream()
                        .filter(ContentBlock.TextContent.class::isInstance)
                        .map(b -> ((ContentBlock.TextContent) b).text())
                        .collect(java.util.stream.Collectors.joining("\n"));
                    yield List.of(Part.fromFunctionResponse(tc.toolUseId(),
                            Map.of("content", text)));
                }
            case ContentBlock.ImageContent ic ->
                    List.of(Part.fromBytes(
                            java.util.Base64.getDecoder().decode(ic.data()),
                            ic.mediaType()));
            case ContentBlock.UrlImageContent url ->
                    // Gemini 走 fileData（URL 图片，P6-19）。
                    List.of(Part.builder()
                            .fileData(com.google.genai.types.FileData.builder()
                                    .fileUri(url.url())
                                    .build())
                            .build());
        };
    }

    private List<FunctionDeclaration> toGoogleFunctions(
            List<ToolDefinition> tools) {
        return tools.stream().<FunctionDeclaration>map(tool -> {
            var builder = FunctionDeclaration.builder()
                    .name(tool.name());
            if (tool.description() != null && !tool.description().isEmpty()) {
                builder.description(tool.description());
            }
            if (tool.inputSchema() != null) {
                builder.parametersJsonSchema(tool.inputSchema());
            }
            return builder.build();
        }).toList();
    }

    private String extractSystemText(List<Message> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            if (msg instanceof Message.SystemMessage) {
                for (var block : msg.content()) {
                    if (block instanceof ContentBlock.TextContent tc) {
                        sb.append(tc.text());
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String resolveApiKey(ApiOptions options) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        var env = System.getenv("GEMINI_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException(
                "No Gemini API key found. Set GEMINI_API_KEY or pass apiKey in ApiOptions.");
    }

}
