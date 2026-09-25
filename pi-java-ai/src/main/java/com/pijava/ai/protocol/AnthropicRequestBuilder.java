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
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.utils.SanitizeUnicode;

/** Builds the Anthropic Messages request after shared message transformation. */
final class AnthropicRequestBuilder {

    private static final String INTERLEAVED_THINKING_BETA =
            "interleaved-thinking-2025-05-14";

    private AnthropicRequestBuilder() {
    }

    static MessageCreateParams buildParams(StreamRequest request) {
        var transcript = Transcripts.resolveTranscript(request.transcript(), request.model());
        var messages = TransformMessages.apply(
                transcript.messages(), request.modelId(), "anthropic-messages", request.model(),
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

        // 系统文本来自**前导系统消息**（pi :1043-1044），随后把它从会话数组里**切掉**
        // （pi :1044 的 `transformedMessages.slice(1)`）—— 否则它会作为普通消息落线
        // （旧实现就是那样：msg 不是 UserMessage ⇒ 走 ASSISTANT 分支，静默错发）。
        var initialSystemMessage = Transcripts.getInitialSystemMessage(transcript.messages());
        var systemText = initialSystemMessage == null
                ? "" : MessageTexts.getSystemMessageText(initialSystemMessage);
        if (!systemText.isEmpty()) {
            builder.system(SanitizeUnicode.surrogates(systemText));
        }

        // ⚠️ 切头用的是**变换后**的表（pi 同上），但「有没有头」按**变换前**判定 ——
        // pi :1042-1044 正是这么写的，而第二遍（孤儿合成）不会移动下标 0 的系统消息。
        var conversation = initialSystemMessage == null
                ? messages : messages.subList(1, messages.size());
        // 中途系统消息的原生渲染归 A3/A7（docs/49 L-D）；折叠后本断言恒成立。
        Transcripts.requireOnlyLeadingSystemMessage(conversation, "anthropic-messages");

        for (int i = 0; i < conversation.size(); i++) {
            var msg = conversation.get(i);
            if (msg instanceof Message.ToolResultMessage) {
                var resultBlocks = new ArrayList<ContentBlockParam>();
                int j = i;
                while (j < conversation.size()
                        && conversation.get(j) instanceof Message.ToolResultMessage tool) {
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

        for (var td : Transcripts.getCurrentTools(transcript.messages())) {
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
