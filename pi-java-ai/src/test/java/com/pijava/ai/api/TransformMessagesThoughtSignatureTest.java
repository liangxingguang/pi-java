package com.pijava.ai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;

/**
 * <b>Batch F 步 8</b>（{@code 原 docs/67}）：跨模型重放时剥离
 * {@code thoughtSignature}（pi {@code transform-messages.ts:131-136}）—— 别的模型
 * 产生的签名对当前模型不可解密，不能发出去；剥离与 id 归一可叠加。
 */
class TransformMessagesThoughtSignatureTest {

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");
    private static final String API = "anthropic-messages";

    /** Visual model: no image gate interference. */
    private static final ModelInfo VISION = new ModelInfo(
        TARGET, "Claude Sonnet 5",
        java.util.Set.of(com.pijava.ai.model.ModelCapability.TEXT,
            com.pijava.ai.model.ModelCapability.IMAGE_INPUT),
        200_000, 8_192, false, com.pijava.ai.model.PricingInfo.UNKNOWN);

    private static Message.AssistantMessage foreign(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            API, "teamorouter", "deepseek-v4-flash", null, null, null, null);
    }

    private static ContentBlock.ToolUseContent toolCall(String signature) {
        return new ContentBlock.ToolUseContent(
            "call_1", "read", Map.of("path", "a"), signature);
    }

    private static List<Message> apply(List<Message> messages, ToolCallIdNormalizer normalizer) {
        return TransformMessages.apply(messages, TARGET, API, VISION, normalizer);
    }

    @Test
    void stripsSignatureFromForeignToolCall() {
        var result = apply(List.of(foreign(toolCall("U0lHTg=="))), null);

        var block = (ContentBlock.ToolUseContent)
            ((Message.AssistantMessage) result.get(0)).content().get(0);
        assertThat(block.thoughtSignature())
            .as("跨模型：thoughtSignature 必须剥离，id 不动")
            .isNull();
        assertThat(block.id()).isEqualTo("call_1");
    }

    @Test
    void keepsSameModelSignature() {
        var same = new Message.AssistantMessage(List.of(toolCall("U0lHTg==")),
            "stop", null, API, TARGET.provider(), TARGET.modelName(),
            null, null, null, null);

        var block = (ContentBlock.ToolUseContent)
            ((Message.AssistantMessage) apply(List.of(same), null).get(0))
                .content().get(0);
        assertThat(block.thoughtSignature()).isEqualTo("U0lHTg==");
    }

    @Test
    void signatureStrippingStacksWithIdNormalization() {
        ToolCallIdNormalizer normalizer = (id, target, source) -> "normalized-id";

        var result = apply(List.of(foreign(toolCall("U0lHTg=="))), normalizer);

        var block = (ContentBlock.ToolUseContent)
            ((Message.AssistantMessage) result.get(0)).content().get(0);
        assertThat(block.id())
            .as("剥离与 id 归一两个变换必须叠加").isEqualTo("normalized-id");
        assertThat(block.thoughtSignature()).isNull();
    }
}
