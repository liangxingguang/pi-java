package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SubmissionPublisher;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;

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
import com.pijava.ai.stream.StreamEvent.ToolCallEnd;
import com.pijava.ai.stream.StreamEvent.ToolCallStart;
import com.pijava.ai.stream.StreamEvent.UsageInfo;

/**
 * Anthropic Messages API adapter using the official {@code anthropic-java} SDK.
 *
 * <p>Phase 1: text-only streaming. Image and tool support will be added incrementally
 * as the SDK wrapped-type APIs are mapped.</p>
 */
public final class AnthropicMessagesApi implements ChatApi {

    private final AnthropicClient client;

    public AnthropicMessagesApi(ApiOptions options) {
        var apiKey = resolveApiKey(options);
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
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
            try (StreamResponse<RawMessageStreamEvent> sr =
                         client.messages().createStreaming(params)) {
                sr.stream().forEach(raw -> {
                    StreamEvent se = mapEvent(raw);
                    if (se != null) publisher.submit(se);
                });
            }
        } catch (Exception e) {
            publisher.submit(new StreamError(e));
        }
    }

    private MessageCreateParams buildParams(StreamRequest request) {
        var builder = MessageCreateParams.builder()
                .model(request.model().modelName())
                .maxTokens(request.maxTokens() > 0 ? request.maxTokens() : 4096L);

        var systemText = extractSystemText(request.messages());
        if (!systemText.isEmpty()) {
            builder.system(systemText);
        }

        for (var msg : request.messages()) {
            if (msg instanceof Message.SystemMessage) continue;
            var text = extractText(msg.content());
            if (text.isEmpty()) continue;

            var role = msg instanceof Message.UserMessage
                    ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;

            builder.addMessage(MessageParam.builder()
                    .role(role)
                    .content(MessageParam.Content.ofBlockParams(
                            List.of(ContentBlockParam.ofText(
                                    TextBlockParam.builder().text(text).build()))))
                    .build());
        }

        // TODO: add tool support (requires JsonValue conversion for inputSchema)
        // TODO: add image support (requires Base64ImageSource wrapping)

        if (request.temperature() >= 0) {
            builder.temperature(request.temperature());
        }

        return builder.build();
    }

    private StreamEvent mapEvent(RawMessageStreamEvent event) {
        try {
            if (event.isContentBlockStart()) {
                var block = event.asContentBlockStart().contentBlock();
                if (block.isToolUse()) {
                    var tu = block.toolUse().orElseThrow();
                    return new ToolCallStart(tu.id(), tu.name());
                }
                return null;
            }
            if (event.isContentBlockDelta()) {
                var delta = event.asContentBlockDelta().delta();
                if (delta.isText()) {
                    return new TextDelta(delta.asText().text(), TextDelta.TEXT);
                }
                if (delta.isInputJson()) {
                    return new ToolCallDelta("",
                            delta.asInputJson().partialJson());
                }
                return null;
            }
            if (event.isContentBlockStop()) return null;
            if (event.isMessageDelta()) {
                var usage = event.asMessageDelta().usage();
                return new UsageInfo(
                        usage.inputTokens().orElse(0L),
                        usage.outputTokens());
            }
            if (event.isMessageStop()) {
                return new StreamDone("end_turn", null);
            }
        } catch (Exception e) {
            return new StreamError(e);
        }
        return null;
    }

    private String extractSystemText(List<Message> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            if (msg instanceof Message.SystemMessage) {
                for (var block : msg.content()) {
                    if (block instanceof ContentBlock.TextContent tc) sb.append(tc.text());
                }
            }
        }
        return sb.toString();
    }

    private String extractText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) sb.append(tc.text());
        }
        return sb.toString();
    }

    private static String resolveApiKey(ApiOptions options) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) return options.apiKey();
        var env = System.getenv("ANTHROPIC_API_KEY");
        if (env != null && !env.isBlank()) return env;
        throw new IllegalStateException(
                "No Anthropic API key. Set ANTHROPIC_API_KEY or pass apiKey.");
    }
}
