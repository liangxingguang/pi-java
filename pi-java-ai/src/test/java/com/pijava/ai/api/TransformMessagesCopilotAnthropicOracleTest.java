package com.pijava.ai.api;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.protocol.AnthropicToolCallIds;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Four migration oracles copied from pi's
 * {@code transform-messages-copilot-openai-to-anthropic.test.ts} at commit
 * {@code 3390bd936}.
 *
 * <p>Each behavior case calls the public five-argument transform entry point,
 * including the real Anthropic tool-call-id normalizer. The source is an
 * OpenAI/Copilot assistant and the target is Copilot's Anthropic Claude model;
 * this is the cross-model session-migration shape covered by pi.</p>
 */
class TransformMessagesCopilotAnthropicOracleTest {

    private static final String TARGET_API = "anthropic-messages";
    private static final ModelId<?> TARGET =
        ModelId.of("github-copilot", "claude-sonnet-4.6");
    private static final ModelInfo TARGET_INFO = new ModelInfo(
        TARGET,
        "Claude Sonnet 4.6",
        Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT,
            ModelCapability.TOOL_USE, ModelCapability.THINKING),
        128_000,
        16_000,
        false,
        PricingInfo.UNKNOWN);

    private static Message.AssistantMessage foreignAssistant(String api, String model,
                                                              String stopReason,
                                                              ContentBlock... blocks) {
        return new Message.AssistantMessage(
            List.of(blocks),
            stopReason,
            null,
            api,
            "github-copilot",
            model,
            null,
            null,
            null,
            null);
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message.ToolResultMessage result(String id, String name, String text,
                                                     boolean isError) {
        return new Message.ToolResultMessage(
            id,
            name,
            List.of(new ContentBlock.TextContent(text)),
            isError);
    }

    private static List<Message> apply(List<Message> messages) {
        return TransformMessages.apply(
            messages,
            TARGET,
            TARGET_API,
            TARGET_INFO,
            AnthropicToolCallIds.create());
    }

    /**
     * Pi's first oracle: a thinking block from the OpenAI/Copilot source is
     * downgraded to text when replayed to the different Anthropic model, while
     * the ordinary text remains. This turns red if the cross-model thinking
     * gate preserves {@link ContentBlock.ThinkingContent}, drops its text, or
     * loses the neighboring text block.
     */
    @Test
    void crossModelThinkingIsDowngradedToText() {
        var assistant = foreignAssistant(
            "openai-completions", "gpt-4o", "stop",
            new ContentBlock.ThinkingContent("Let me think about this...", "reasoning_content"),
            new ContentBlock.TextContent("Hi there!"));

        var output = apply(List.of(user("hello"), assistant));
        var transformed = (Message.AssistantMessage) output.get(1);

        assertThat(transformed.content().stream()
            .filter(ContentBlock.ThinkingContent.class::isInstance)
            .toList())
            .isEmpty();
        assertThat(transformed.content())
            .containsExactly(
                new ContentBlock.TextContent("Let me think about this..."),
                new ContentBlock.TextContent("Hi there!"));
    }

    /**
     * Pi's second oracle supplies a {@code thoughtSignature} on a tool call
     * and expects it removed during migration. Java's {@link
     * ContentBlock.ToolUseContent} has no such record component, so the oracle
     * is structurally inapplicable: there is no signature to strip and no
     * production behavior to fake. The five-argument pass is still exercised
     * with the corresponding tool call, while reflection records the shape
     * evidence. This test will not turn red from deleting a nonexistent
     * production stripping branch; it turns red only if Java accidentally
     * grows the forbidden field.
     */
    @Test
    void toolUseShapeHasNoThoughtSignatureComponent() {
        var output = apply(List.of(
            user("run a command"),
            foreignAssistant("openai-responses", "gpt-5", "toolUse",
                new ContentBlock.ToolUseContent(
                    "call_123", "bash", Map.of("command", "ls")))));
        var toolUse = ((Message.AssistantMessage) output.get(1)).content().stream()
            .filter(ContentBlock.ToolUseContent.class::isInstance)
            .map(ContentBlock.ToolUseContent.class::cast)
            .findFirst()
            .orElseThrow();

        assertThat(Arrays.stream(ContentBlock.ToolUseContent.class.getRecordComponents())
            .map(component -> component.getName()))
            .doesNotContain("thoughtSignature");
        assertThat(toolUse.id()).isEqualTo("call_123");
        assertThat(toolUse.name()).isEqualTo("bash");
    }

    /**
     * Pi's third oracle synthesizes a missing result after a trailing
     * {@code call_123|fc_123}; the Anthropic normalizer first makes its id
     * {@code call_123_fc_123}, then the second transform pass appends one
     * error result with the exact {@code No result provided} text. This turns
     * red if either pass is skipped, ordered incorrectly, or emits the wrong
     * id/name/error payload.
     */
    @Test
    void trailingOrphanGetsNormalizedSyntheticErrorResult() {
        var output = apply(List.of(
            user("read the file"),
            foreignAssistant("openai-responses", "gpt-5", "toolUse",
                new ContentBlock.ToolUseContent(
                    "call_123|fc_123", "read", Map.of("path", "README.md")))));

        var synthetic = (Message.ToolResultMessage) output.getLast();
        assertThat(synthetic.toolUseId()).isEqualTo("call_123_fc_123");
        assertThat(synthetic.toolName()).isEqualTo("read");
        assertThat(synthetic.isError()).isTrue();
        assertThat(synthetic.content())
            .containsExactly(new ContentBlock.TextContent("No result provided"));
    }

    /**
     * Pi's fourth oracle keeps an answered first call and synthesizes only the
     * still-missing second call. This turns red if id normalization is not
     * shared with the orphan pass, if the real result is mistaken for an
     * orphan, or if more than one synthetic result is emitted.
     */
    @Test
    void selectiveOrphanSynthesisKeepsRealResultAndAddsOneMissingResult() {
        var output = apply(List.of(
            user("run commands"),
            foreignAssistant("openai-responses", "gpt-5", "toolUse",
                new ContentBlock.ToolUseContent("call_1|fc_1", "read", Map.of()),
                new ContentBlock.ToolUseContent("call_2|fc_2", "bash", Map.of())),
            result("call_1|fc_1", "read", "done", false)));

        var toolResults = output.stream()
            .filter(Message.ToolResultMessage.class::isInstance)
            .map(Message.ToolResultMessage.class::cast)
            .toList();
        assertThat(toolResults).hasSize(2);
        assertThat(toolResults.stream().filter(Message.ToolResultMessage::isError).toList())
            .hasSize(1);

        var real = toolResults.getFirst();
        assertThat(real.toolUseId()).isEqualTo("call_1_fc_1");
        assertThat(real.toolName()).isEqualTo("read");
        assertThat(real.isError()).isFalse();

        var synthetic = toolResults.stream()
            .filter(Message.ToolResultMessage::isError)
            .findFirst()
            .orElseThrow();
        assertThat(synthetic.toolUseId()).isEqualTo("call_2_fc_2");
        assertThat(synthetic.toolName()).isEqualTo("bash");
        assertThat(synthetic.content())
            .containsExactly(new ContentBlock.TextContent("No result provided"));
    }
}
