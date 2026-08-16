package com.pijava.ai.protocol;

import java.util.List;
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
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * Anthropic Messages API adapter using the official {@code anthropic-java} SDK.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots
 * via {@link StreamPartialBuilder}. Handles text, thinking, and tool-call blocks.</p>
 */
public final class AnthropicMessagesApi extends AbstractChatApi {

    private final AnthropicClient client;

    /**
     * Create an adapter for the given options.
     *
     * @param options API options (apiKey or {@code ANTHROPIC_API_KEY} required)
     */
    public AnthropicMessagesApi(ApiOptions options) {
        var apiKey = resolveApiKey(options);
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    @Override
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        var isToolBlock = new boolean[]{false};
        var isThinkingBlock = new boolean[]{false};
        var pendingToolName = new String[]{""};
        var pendingToolId = new String[]{""};
        try {
            var params = buildParams(request);
            publisher.submit(builder.emitStart());

            try (StreamResponse<RawMessageStreamEvent> sr =
                         client.messages().createStreaming(params)) {
                sr.stream().forEach(raw -> {
                    StreamEvent se = mapEvent(raw, builder, isToolBlock,
                            isThinkingBlock, pendingToolName, pendingToolId);
                    if (se != null) publisher.submit(se);
                });
            }
            publisher.submit(builder.emitDone("end_turn"));
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
    }

    private StreamEvent mapEvent(RawMessageStreamEvent event,
                                  StreamPartialBuilder builder,
                                  boolean[] isToolBlock,
                                  boolean[] isThinkingBlock,
                                  String[] pendingToolName,
                                  String[] pendingToolId) {
        try {
            if (event.isContentBlockStart()) {
                var block = event.asContentBlockStart().contentBlock();
                if (block.isToolUse()) {
                    var tu = block.toolUse().orElseThrow();
                    isToolBlock[0] = true;
                    isThinkingBlock[0] = false;
                    pendingToolName[0] = tu.name();
                    pendingToolId[0] = tu.id();
                    return builder.emitToolCallStart();
                }
                if (block.isThinking()) {
                    isToolBlock[0] = false;
                    isThinkingBlock[0] = true;
                    return builder.emitThinkingStart();
                }
                isToolBlock[0] = false;
                isThinkingBlock[0] = false;
                return builder.emitTextStart();
            }
            if (event.isContentBlockDelta()) {
                var delta = event.asContentBlockDelta().delta();
                if (delta.isText()) {
                    return builder.emitTextDelta(delta.asText().text());
                }
                if (delta.isInputJson()) {
                    return builder.emitToolCallDelta(pendingToolId[0],
                            delta.asInputJson().partialJson());
                }
                if (delta.isThinking()) {
                    return builder.emitThinkingDelta(delta.asThinking().thinking());
                }
                if (delta.isSignature()) {
                    return null;
                }
                return null;
            }
            if (event.isContentBlockStop()) {
                if (isToolBlock[0]) {
                    return builder.emitToolCallEnd(
                            pendingToolId[0], pendingToolName[0]);
                }
                if (isThinkingBlock[0]) {
                    return builder.emitThinkingEnd();
                }
                return builder.emitTextEnd();
            }
            if (event.isMessageDelta()) {
                var usage = event.asMessageDelta().usage();
                return builder.emitUsage(
                        usage.inputTokens().orElse(0L),
                        usage.outputTokens());
            }
            if (event.isMessageStop()) {
                return null; // StreamDone emitted in streamInternal finally
            }
        } catch (Exception e) {
            return builder.emitError("error", e);
        }
        return null;
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

        if (request.temperature() >= 0) {
            builder.temperature(request.temperature());
        }

        return builder.build();
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
