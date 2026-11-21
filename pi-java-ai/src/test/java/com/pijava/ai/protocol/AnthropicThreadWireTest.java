package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code anthropic-messages.ts:357-382} 的**消息面**：{@code continue} 只发尾部、省略
 * {@code system} 与 {@code tools}，而 {@code messageCount} 始终按完整请求计。
 *
 * <p>线上 {@code thread} 字段的观测属车道级（{@code RecordingHttpServer}），见 S7b 的用例。</p>
 */
class AnthropicThreadWireTest {

    private static final String BASE = "https://api.anthropic.com";

    private static StreamRequest request() {
        return new StreamRequest(
            ModelId.of("anthropic", "claude-sonnet-5"),
            "be brief",
            List.of(
                new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(new ContentBlock.TextContent("hello")),
                    StopReason.STOP, null, "anthropic-messages", "anthropic",
                    "claude-sonnet-5", null, null, null, null),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("next")))),
            List.of(new ToolDefinition("ls", "List files",
                Map.of("type", "object", "properties", Map.of()))),
            100, 0.5);
    }

    private static AnthropicRequestBuilder.BuiltParams build(
            AnthropicThreadWire.ThreadRequest thread) {
        return AnthropicRequestBuilder.buildParams(request(), Optional.empty(), null, BASE,
            Set.of(), false, false, thread);
    }

    @Test
    void createSendsTheFullRequest() {
        var built = build(AnthropicThreadWire.ThreadRequest.create());

        assertThat(built.params().messages()).hasSize(3);
        assertThat(built.params().system()).isPresent();
        assertThat(built.params().tools()).isPresent();
        assertThat(built.messageCount()).isEqualTo(3);
    }

    @Test
    void continueSendsOnlyTheTailAndOmitsSystemAndTools() {
        var built = build(AnthropicThreadWire.ThreadRequest.continueFrom(2, "msg_1"));

        assertThat(built.params().messages()).hasSize(1);
        assertThat(built.params().system()).isEmpty();
        assertThat(built.params().tools()).isEmpty();
        assertThat(built.messageCount()).as("计数按完整请求").isEqualTo(3);
    }
}
