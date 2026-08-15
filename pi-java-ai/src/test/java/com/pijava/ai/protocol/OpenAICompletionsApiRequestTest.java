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

        var params = OpenAICompletionsApi.buildParams(request);

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
        var request = StreamRequest.of(ModelId.of("openai", "gpt-4o-mini"),
            List.of(
                new Message.SystemMessage(List.of(
                    new ContentBlock.TextContent("be concise"))),
                new Message.UserMessage(List.of(
                    new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.TextContent("hello!")))));

        var params = OpenAICompletionsApi.buildParams(request);

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
        var request = StreamRequest.of(ModelId.of("deepseek", "deepseek-chat"),
            List.of(
                new Message.UserMessage(List.of(
                    new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ThinkingContent("let me reason"),
                    new ContentBlock.TextContent("answer")))));

        var params = OpenAICompletionsApi.buildParams(request);

        var assistant = params.messages().stream()
            .filter(ChatCompletionMessageParam::isAssistant)
            .map(ChatCompletionMessageParam::asAssistant)
            .findFirst().orElseThrow();
        assertThat(assistant.content().get().asText()).isEqualTo("answer");
        assertThat(assistant._additionalProperties())
            .containsKey("reasoning_content");
    }

    @Test
    void nonDeepseekThinkingIsNotRoundTripped() {
        var request = StreamRequest.of(ModelId.of("openai", "gpt-4o-mini"),
            List.of(
                new Message.UserMessage(List.of(
                    new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ThinkingContent("let me reason"),
                    new ContentBlock.TextContent("answer")))));

        var params = OpenAICompletionsApi.buildParams(request);

        var assistant = params.messages().stream()
            .filter(ChatCompletionMessageParam::isAssistant)
            .map(ChatCompletionMessageParam::asAssistant)
            .findFirst().orElseThrow();
        assertThat(assistant.content().get().asText()).isEqualTo("answer");
        assertThat(assistant._additionalProperties())
            .doesNotContainKey("reasoning_content");
    }
}
