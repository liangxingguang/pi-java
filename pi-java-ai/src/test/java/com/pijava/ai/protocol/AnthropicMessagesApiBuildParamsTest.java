package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anthropic adapter must round-trip tool definitions, assistant tool_use
 * blocks, and tool results — without them the model cannot invoke tools or
 * see their outcomes (observed as text-form tool calls and runs that never
 * execute tools).
 */
class AnthropicMessagesApiBuildParamsTest {

    private MessageCreateParams buildParams(StreamRequest request) throws Exception {
        var options = new ApiOptions(
            "https://api.teamorouter.cn", "sk-test",
            Duration.ofSeconds(10), 1, Map.of());
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        Method method = AnthropicMessagesApi.class.getDeclaredMethod(
            "buildParams", StreamRequest.class);
        method.setAccessible(true);
        return (MessageCreateParams) method.invoke(api, request);
    }

    @Test
    void passesToolsToTheRequest() throws Exception {
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(new ToolDefinition(
                "ls", "List directory contents",
                Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string"))))),
            100, 0.5, Map.of());

        var params = buildParams(request);

        var tools = params.tools();
        assertThat(tools).isPresent();
        assertThat(tools.get()).hasSize(1);
        assertThat(tools.get().get(0).tool()).isPresent();
    }

    @Test
    void roundTripsAssistantToolUseBlock() throws Exception {
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(
                new Message.UserMessage(
                    List.of(new ContentBlock.TextContent("list files"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.TextContent("I'll list the files."),
                    new ContentBlock.ToolUseContent(
                        "toolu_01", "ls", Map.of("path", "."))))),
            List.of(), 100, 0.5, Map.of());

        var params = buildParams(request);

        var messages = params.messages();
        assertThat(messages).hasSize(2);
        var assistant = messages.get(1).content();
        var blocks = assistant.asBlockParams();
        assertThat(blocks).anyMatch(b -> b.isToolUse());
    }

    @Test
    void roundTripsToolResultMessage() throws Exception {
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(
                new Message.UserMessage(
                    List.of(new ContentBlock.TextContent("list files"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ToolUseContent(
                        "toolu_01", "ls", Map.of("path", ".")))),
                new Message.ToolResultMessage(
                    "toolu_01", "ls",
                    List.of(new ContentBlock.TextContent("a.txt\nb.txt")), false)),
            List.of(), 100, 0.5, Map.of());

        var params = buildParams(request);

        var messages = params.messages();
        assertThat(messages).hasSize(3);
        var result = messages.get(2).content();
        var blocks = result.asBlockParams();
        assertThat(blocks).anyMatch(b -> b.isToolResult());
    }

    @Test
    void sendsToolResultAsUserRole() throws Exception {
        // Anthropic requires tool_result blocks inside a user message
        // (pi anthropic-messages.ts maps toolResult -> role "user").
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(
                new Message.UserMessage(
                    List.of(new ContentBlock.TextContent("list files"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ToolUseContent(
                        "toolu_01", "ls", Map.of("path", ".")))),
                new Message.ToolResultMessage(
                    "toolu_01", "ls",
                    List.of(new ContentBlock.TextContent("a.txt")), false)),
            List.of(), 100, 0.5, Map.of());

        var params = buildParams(request);

        var messages = params.messages();
        assertThat(messages.get(2).role())
            .isEqualTo(MessageParam.Role.USER);
    }

    @Test
    void mergesConsecutiveToolResultsIntoOneUserMessage() throws Exception {
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(
                new Message.UserMessage(
                    List.of(new ContentBlock.TextContent("list files"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ToolUseContent(
                        "toolu_01", "ls", Map.of("path", ".")),
                    new ContentBlock.ToolUseContent(
                        "toolu_02", "ls", Map.of("path", "..")))),
                new Message.ToolResultMessage(
                    "toolu_01", "ls",
                    List.of(new ContentBlock.TextContent("a.txt")), false),
                new Message.ToolResultMessage(
                    "toolu_02", "ls",
                    List.of(new ContentBlock.TextContent("b.txt")), false)),
            List.of(), 100, 0.5, Map.of());

        var params = buildParams(request);

        var messages = params.messages();
        // user, assistant, one merged user message carrying both tool_results
        assertThat(messages).hasSize(3);
        assertThat(messages.get(2).role()).isEqualTo(MessageParam.Role.USER);
        var blocks = messages.get(2).content().asBlockParams();
        assertThat(blocks).filteredOn(b -> b.isToolResult()).hasSize(2);
    }

    @Test
    void replaysThinkingBlockWithSignature() throws Exception {
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(
                new Message.UserMessage(
                    List.of(new ContentBlock.TextContent("list files"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ThinkingContent("I should list files.", "sig_abc"),
                    new ContentBlock.ToolUseContent(
                        "toolu_01", "ls", Map.of("path", "."))))),
            List.of(), 100, 0.5, Map.of());

        var params = buildParams(request);

        var messages = params.messages();
        assertThat(messages).hasSize(2);
        var blocks = messages.get(1).content().asBlockParams();
        var thinking = blocks.stream().filter(b -> b.isThinking()).findFirst().orElseThrow();
        assertThat(thinking.asThinking().thinking()).isEqualTo("I should list files.");
        assertThat(thinking.asThinking().signature()).isEqualTo("sig_abc");
    }

    @Test
    void downgradesThinkingWithoutSignatureToText() throws Exception {
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(
                new Message.UserMessage(
                    List.of(new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ThinkingContent("implicit reasoning")))),
            List.of(), 100, 0.5, Map.of());

        var params = buildParams(request);

        var messages = params.messages();
        assertThat(messages).hasSize(2);
        var blocks = messages.get(1).content().asBlockParams();
        assertThat(blocks).noneMatch(b -> b.isThinking());
        assertThat(blocks).anyMatch(b -> b.isText()
            && b.asText().text().equals("implicit reasoning"));
    }

    @Test
    void skipsEmptyThinkingWithoutSignature() throws Exception {
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(
                new Message.UserMessage(
                    List.of(new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(
                    new ContentBlock.ThinkingContent("  ")))),
            List.of(), 100, 0.5, Map.of());

        var params = buildParams(request);

        var messages = params.messages();
        assertThat(messages).hasSize(1);
    }

    @Test
    void extraBudgetTokensEnableThinking() throws Exception {
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 2048, -1,
            Map.of("thinking.budgetTokens", 2048));

        var params = buildParams(request);

        var thinking = params.thinking().orElseThrow();
        assertThat(thinking.isEnabled()).isTrue();
        assertThat(thinking.asEnabled().budgetTokens()).isEqualTo(2048L);
    }

    @Test
    void noBudgetTokensLeaveThinkingUnset() throws Exception {
        var request = new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            null,
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 2048, -1, Map.of());

        var params = buildParams(request);

        assertThat(params.thinking()).isEmpty();
    }
}
