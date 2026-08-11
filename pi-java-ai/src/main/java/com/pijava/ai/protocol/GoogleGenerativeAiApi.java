package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
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
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamEvent.StreamDone;
import com.pijava.ai.stream.StreamEvent.StreamError;
import com.pijava.ai.stream.StreamEvent.TextDelta;
import com.pijava.ai.stream.StreamEvent.ToolCallEnd;
import com.pijava.ai.stream.StreamEvent.ToolCallStart;
import com.pijava.ai.stream.StreamEvent.UsageInfo;

/**
 * Google Gemini API adapter using the official {@code google-genai} SDK.
 *
 * <p>Handles message conversion, promptFeedback safety interception,
 * and functionCall → tool-call event mapping. Google returns complete
 * function-call arguments in a single response (no delta aggregation needed).</p>
 */
public final class GoogleGenerativeAiApi implements ChatApi {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final Client client;

    public GoogleGenerativeAiApi(ApiOptions options) {
        var apiKey = resolveApiKey(options);
        var baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
                ? options.baseUrl() : DEFAULT_BASE_URL;
        this.client = Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().baseUrl(baseUrl).build())
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
    public Message send(StreamRequest request, ApiOptions options) {
        var blocks = new ArrayList<ContentBlock>();
        try (var iter = streamBlocking(request, options)) {
            while (iter.hasNext()) {
                var event = iter.next();
                if (event instanceof TextDelta td) {
                    blocks.add(new ContentBlock.TextContent(td.text()));
                }
            }
        } catch (Exception e) {
            throw new com.pijava.ai.http.PiHttpException(0, "Streaming failed", e);
        }
        return new Message.AssistantMessage(blocks);
    }

    // ── Internals ─────────────────────────────────────────────────

    private void streamInternal(StreamRequest request,
                                SubmissionPublisher<StreamEvent> publisher) {
        try {
            var contents = toGoogleContents(request.messages());
            var config = buildConfig(request);

            try (ResponseStream<GenerateContentResponse> stream =
                         client.models.generateContentStream(
                                 request.model().modelName(), contents, config)) {

                for (var response : stream) {
                    // Safety filter check
                    if (response.promptFeedback().isPresent()) {
                        var fb = response.promptFeedback().get();
                        if (fb.blockReason().isPresent()) {
                            publisher.submit(new StreamError(
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
                        publisher.submit(new UsageInfo(input, output));
                    }

                    // Process candidates
                    if (response.candidates().isEmpty()) continue;
                    for (var candidate : response.candidates().get()) {
                        if (candidate.content().isEmpty()) continue;
                        var parts = candidate.content().get().parts();
                        if (parts.isEmpty()) continue;

                        for (var part : parts.get()) {
                            // Text / thinking
                            if (part.text().isPresent()) {
                                String text = part.text().get();
                                boolean isThinking = part.thought().isPresent()
                                        && part.thought().get();
                                String type = isThinking ? TextDelta.THINKING : TextDelta.TEXT;
                                publisher.submit(new TextDelta(text, type));
                            }

                            // Function call (Google returns complete args — no delta)
                            if (part.functionCall().isPresent()) {
                                FunctionCall fc = part.functionCall().get();
                                String id = fc.id().orElse(
                                        fc.name().orElse("unknown") + "_"
                                                + System.currentTimeMillis());
                                String name = fc.name().orElse("");
                                Map<String, Object> args = fc.args().orElse(Map.of());

                                publisher.submit(new ToolCallStart(id, name));
                                publisher.submit(new ToolCallEnd(id, name, args));
                            }
                        }
                    }

                    // Finish reason
                    var finishReason = response.finishReason();
                    if (finishReason != null) {
                        String reason = finishReason.toString().toLowerCase();
                        publisher.submit(new StreamDone(reason, null));
                    }
                }
            }
        } catch (Exception e) {
            publisher.submit(new StreamError(e));
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
            case ContentBlock.ToolResultContent tc ->
                    List.of(Part.fromFunctionResponse(tc.toolUseId(),
                            Map.of("content", tc.content())));
            case ContentBlock.ImageContent ic ->
                    List.of(Part.fromBytes(
                            java.util.Base64.getDecoder().decode(ic.data()),
                            ic.mediaType()));
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
