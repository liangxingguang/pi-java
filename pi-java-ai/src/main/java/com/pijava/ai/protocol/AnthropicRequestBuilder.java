package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.message.Message;
import com.pijava.ai.utils.SanitizeUnicode;

/** Builds the Anthropic Messages request after shared message transformation. */
final class AnthropicRequestBuilder {

    private static final String INTERLEAVED_THINKING_BETA =
            "interleaved-thinking-2025-05-14";

    private AnthropicRequestBuilder() {
    }

    static MessageCreateParams buildParams(StreamRequest request) {
        var messages = TransformMessages.apply(
                request.messages(), request.modelId(), "anthropic-messages", request.model(),
                AnthropicToolCallIds.create());
        var allowEmptySignature = request.model() != null
                && request.model().compat().allowEmptySignature();
        var thinking = AnthropicThinking.resolve(
                request.model(),
                request.reasoning(),
                request.maxTokens() > 0
                    ? java.util.OptionalInt.of(request.maxTokens())
                    : java.util.OptionalInt.empty());
        var builder = MessageCreateParams.builder()
                .model(request.modelId().modelName())
                .maxTokens(thinking.maxTokens().isPresent()
                    ? thinking.maxTokens().getAsInt()
                    : (request.maxTokens() > 0 ? request.maxTokens() : 4096L));
        thinking.thinking().ifPresent(builder::thinking);
        thinking.outputConfig().ifPresent(builder::outputConfig);

        var systemText = request.systemPrompt();
        if (systemText != null && !systemText.isEmpty()) {
            builder.system(SanitizeUnicode.surrogates(systemText));
        }

        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            if (msg instanceof Message.ToolResultMessage) {
                var resultBlocks = new ArrayList<ContentBlockParam>();
                int j = i;
                while (j < messages.size()
                        && messages.get(j) instanceof Message.ToolResultMessage tool) {
                    resultBlocks.add(AnthropicMessageConverter.toToolResultBlock(tool));
                    j++;
                }
                i = j - 1;
                builder.addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(resultBlocks))
                    .build());
                continue;
            }

            var blockParams = AnthropicMessageConverter.toBlockParams(msg, allowEmptySignature,
                    msg instanceof Message.UserMessage);
            if (blockParams.isEmpty()) {
                continue;
            }
            var role = msg instanceof Message.UserMessage
                    ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
            builder.addMessage(MessageParam.builder()
                    .role(role)
                    .content(MessageParam.Content.ofBlockParams(blockParams))
                    .build());
        }

        for (var td : request.tools()) {
            var inputSchema = Tool.InputSchema.builder()
                    .putAllAdditionalProperties(AnthropicMessageConverter.toJsonValues(td.inputSchema()))
                    .build();
            var toolBuilder = Tool.builder()
                    .name(td.name())
                    .inputSchema(inputSchema);
            if (td.description() != null && !td.description().isBlank()) {
                toolBuilder.description(td.description());
            }
            builder.addTool(ToolUnion.ofTool(toolBuilder.build()));
        }

        if (request.temperature() >= 0 && request.reasoning().isEmpty()) {
            builder.temperature(request.temperature());
        }
        if (request.model() != null
                && request.model().capabilities().contains(com.pijava.ai.model.ModelCapability.THINKING)
                && request.reasoning().isPresent()
                && !request.model().compat().forceAdaptiveThinking()) {
            builder.putAdditionalBodyProperty("betas",
                com.anthropic.core.JsonValue.from(List.of(INTERLEAVED_THINKING_BETA)));
        }
        return builder.build();
    }
}
