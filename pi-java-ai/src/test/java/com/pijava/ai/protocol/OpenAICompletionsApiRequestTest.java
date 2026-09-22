package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.openai.models.chat.completions.ChatCompletionMessageParam;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request serialization for OpenAI-compatible providers (OpenAI, DeepSeek,
 * Mistral, ...): assistant tool calls and tool results must round-trip back
 * to the model. Regression for repeated identical tool executions — without
 * the tool result, the model cannot see the outcome and keeps re-invoking
 * the same tool (rendered as duplicated tool blocks in the TUI).
 */
class OpenAICompletionsApiRequestTest {

    @Test
    void toolResultAndAssistantToolCallsAreSentBack() {
        var request = StreamRequest.of(ModelId.of("deepseek", "deepseek-chat"),
            List.of(
                new Message.UserMessage(List.of(
                    new ContentBlock.TextContent("write hello"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.TextContent("let me write it"),
                    new ContentBlock.ToolUseContent("call_1", "write",
                        Map.of("path", "hello.py",
                            "content", "print(\"hello\")")))),
                new Message.ToolResultMessage("call_1", "write",
                    List.of(new ContentBlock.TextContent(
                        "Successfully wrote 15 bytes to hello.py")),
                    false)));

        var params = OpenAICompletionsMessageConverter.buildParams(request, "openai-completions", null);

        assertThat(params.messages()).hasSize(3);

        var assistant = params.messages().stream()
            .filter(ChatCompletionMessageParam::isAssistant)
            .map(ChatCompletionMessageParam::asAssistant)
            .findFirst().orElseThrow();
        assertThat(assistant.content().get().asText()).isEqualTo("let me write it");
        assertThat(assistant.toolCalls()).isPresent();
        assertThat(assistant.toolCalls().get()).anySatisfy(toolCall -> {
            assertThat(toolCall.isFunction()).isTrue();
            var fn = toolCall.asFunction();
            assertThat(fn.id()).isEqualTo("call_1");
            assertThat(fn.function().name()).isEqualTo("write");
            assertThat(fn.function().arguments()).contains("\"hello.py\"");
        });

        var tool = params.messages().stream()
            .filter(ChatCompletionMessageParam::isTool)
            .map(ChatCompletionMessageParam::asTool)
            .findFirst().orElseThrow();
        assertThat(tool.toolCallId()).isEqualTo("call_1");
        assertThat(tool.content().asText()).contains("Successfully wrote");
    }

    @Test
    void plainTextMessagesKeepRoundTripping() {
        // 系统提示走请求上的独立字段，不在消息列表里 —— pi 的 Message 没有 system 角色。
        var request = new StreamRequest(
            ModelId.of("openai", "gpt-4o-mini"),
            "be concise",
            List.of(
                new Message.UserMessage(List.of(
                    new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.TextContent("hello!")))),
            List.of(), -1, -1, java.util.Map.of());

        var params = OpenAICompletionsMessageConverter.buildParams(request, "openai-completions", null);

        assertThat(params.messages()).hasSize(3);
        assertThat(params.messages().stream()
                .anyMatch(ChatCompletionMessageParam::isSystem)).isTrue();
        assertThat(params.messages().stream()
                .anyMatch(ChatCompletionMessageParam::isUser)).isTrue();
        assertThat(params.messages().stream()
                .anyMatch(ChatCompletionMessageParam::isAssistant)).isTrue();
        assertThat(params.messages().stream()
                .noneMatch(ChatCompletionMessageParam::isTool)).isTrue();
    }
    @Test
    void deepseekThinkingContentIsRoundTripped() {
        // ⚠️ 助手消息必须带**身份**（api/provider/model）：共享预通道
        // TransformMessages 按「同模型否」决定 thinking 块留还是降级为文本，
        // 不带身份的消息会被判成**跨模型**（docs/31 §8.35.5 的接线）。
        //
        // ⚠️ 决定发回哪个字段的是 **签名**（收侧抄下来的线格字段名，pi
        // openai-completions.ts:1310-1318），不是 provider —— B19 之前这里靠
        // 「provider == deepseek 就发 reasoning_content」近似，签名反而被丢掉。
        var request = StreamRequest.of(ModelId.of("deepseek", "deepseek-chat"),
            List.of(
                new Message.UserMessage(List.of(
                    new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ThinkingContent("let me reason", "reasoning_content"),
                    new ContentBlock.TextContent("answer")),
                    "stop", null, "openai-completions", "deepseek", "deepseek-chat",
                    null, null, null, null)));

        var params = OpenAICompletionsMessageConverter.buildParams(request, "openai-completions", null);

        var assistant = params.messages().stream()
            .filter(ChatCompletionMessageParam::isAssistant)
            .map(ChatCompletionMessageParam::asAssistant)
            .findFirst().orElseThrow();
        assertThat(assistant.content().get().asText()).isEqualTo("answer");
        assertThat(assistant._additionalProperties())
            .containsKey("reasoning_content");
        assertThat(assistant._additionalProperties().get("reasoning_content"))
            .isEqualTo(com.openai.core.JsonValue.from("let me reason"));
    }

    @Test
    void unsignedThinkingIsNotRoundTripped() {
        // 决定权在签名：没有签名（空串）的 thinking 块**不发**，与 provider 无关 ——
        // 这正是 B19 从「按 provider 名开闸」改成「按签名自描述」的要点。
        var request = StreamRequest.of(ModelId.of("openai", "gpt-4o-mini"),
            List.of(
                new Message.UserMessage(List.of(
                    new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ThinkingContent("let me reason"),
                    new ContentBlock.TextContent("answer")),
                    "stop", null, "openai-completions", "openai", "gpt-4o-mini",
                    null, null, null, null)));

        var params = OpenAICompletionsMessageConverter.buildParams(request, "openai-completions", null);

        var assistant = params.messages().stream()
            .filter(ChatCompletionMessageParam::isAssistant)
            .map(ChatCompletionMessageParam::asAssistant)
            .findFirst().orElseThrow();
        assertThat(assistant.content().get().asText()).isEqualTo("answer");
        assertThat(assistant._additionalProperties())
            .doesNotContainKey("reasoning_content");
    }
}
