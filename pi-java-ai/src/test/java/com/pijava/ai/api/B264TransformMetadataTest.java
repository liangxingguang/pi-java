package com.pijava.ai.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Both ToolUseContent copy branches and the assistant rebuild retain B264 metadata. */
class B264TransformMetadataTest {

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");
    private static final ModelInfo TARGET_INFO = new ModelInfo(TARGET, "Claude",
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
            200_000, 8_192, false, PricingInfo.UNKNOWN);

    @Test
    void signatureStripBranchRetainsArgumentsErrorAndResponseId() {
        Message.AssistantMessage source = assistant(new ContentBlock.ToolUseContent(
                "call-1", "read", Map.of(), "foreign-signature", "ns", "bad-json"));

        List<Message> out = TransformMessages.apply(List.of(source), TARGET,
                "anthropic-messages", TARGET_INFO);

        Message.AssistantMessage transformed = (Message.AssistantMessage) out.getFirst();
        ContentBlock.ToolUseContent call = (ContentBlock.ToolUseContent) transformed.content().getFirst();
        assertThat(call.thoughtSignature()).isNull();
        assertThat(call.argumentsError()).isEqualTo("bad-json");
        assertThat(transformed.responseId()).isEqualTo("response-1");
    }

    @Test
    void idNormalizationBranchRetainsArgumentsErrorAndResponseId() {
        Message.AssistantMessage source = assistant(new ContentBlock.ToolUseContent(
                "call|1", "read", Map.of(), "foreign-signature", "ns", "bad-json"));
        ToolCallIdNormalizer normalizer = (id, target, message) -> id.replace('|', '_');

        List<Message> out = TransformMessages.apply(List.of(source), TARGET,
                "anthropic-messages", TARGET_INFO, normalizer);

        Message.AssistantMessage transformed = (Message.AssistantMessage) out.getFirst();
        ContentBlock.ToolUseContent call = (ContentBlock.ToolUseContent) transformed.content().getFirst();
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.argumentsError()).isEqualTo("bad-json");
        assertThat(transformed.responseId()).isEqualTo("response-1");
    }

    private static Message.AssistantMessage assistant(ContentBlock block) {
        return new Message.AssistantMessage(List.of(block), StopReason.STOP, null,
                "anthropic-messages", "foreign", "foreign-model", null, null, null,
                null, null, null, null, null, null, "response-1");
    }
}
