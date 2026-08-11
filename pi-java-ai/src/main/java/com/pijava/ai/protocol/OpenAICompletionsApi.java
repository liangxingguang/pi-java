package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SubmissionPublisher;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamEvent.StreamDone;
import com.pijava.ai.stream.StreamEvent.StreamError;
import com.pijava.ai.stream.StreamEvent.TextDelta;
import com.pijava.ai.stream.StreamEvent.ToolCallDelta;
import com.pijava.ai.stream.StreamEvent.ToolCallStart;
import com.pijava.ai.stream.StreamEvent.UsageInfo;

/**
 * OpenAI Chat Completions adapter using the official {@code openai-java} SDK.
 *
 * <p>Phase 1: text-only streaming using SDK convenience methods
 * ({@code addUserMessage(String)}, {@code addSystemMessage(String)}).</p>
 */
public class OpenAICompletionsApi implements ChatApi {

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
        return new AiQueueStreamIterator(queue);
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

    private void streamInternal(StreamRequest request,
                                 SubmissionPublisher<StreamEvent> publisher) {
        try {
            var params = buildParams(request);
            try (var streamResponse = client.chat().completions().createStreaming(params)) {
                streamResponse.stream().forEach(chunk -> {
                    if (chunk.choices().isEmpty()) return;
                    var choice = chunk.choices().get(0);
                    var delta = choice.delta();

                    delta.content().ifPresent(text -> {
                        if (!text.isEmpty()) publisher.submit(
                                new TextDelta(text, TextDelta.TEXT));
                    });

                    delta.toolCalls().ifPresent(toolCalls -> {
                        for (var tc : toolCalls) {
                            if (tc.id().isPresent() && tc.function().isPresent()) {
                                var func = tc.function().get();
                                publisher.submit(new ToolCallStart(
                                        tc.id().get(), func.name().orElse("")));
                                func.arguments().ifPresent(args ->
                                        publisher.submit(new ToolCallDelta(
                                                tc.id().get(), args)));
                            }
                        }
                    });

                    chunk.usage().ifPresent(u -> publisher.submit(
                            new UsageInfo(u.promptTokens(), u.completionTokens())));
                });
            }
        } catch (Exception e) {
            publisher.submit(new StreamError(e));
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
