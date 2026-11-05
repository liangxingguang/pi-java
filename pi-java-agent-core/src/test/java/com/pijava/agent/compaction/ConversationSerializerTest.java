package com.pijava.agent.compaction;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B272（docs/24 W2）：摘要序列化不再输出 thinking —— pi
 * {@code compaction/utils.ts:108-150}（Thinking is omitted…）。
 */
class ConversationSerializerTest {

    @Test
    void thinkingIsOmittedButTextAndToolCallsRemain() {
        var assistant = new Message.AssistantMessage(
            List.of(
                new ContentBlock.ThinkingContent("internal reasoning"),
                new ContentBlock.TextContent("answer"),
                new ContentBlock.ToolUseContent("call-1", "read", Map.of("path", "a.txt"))),
            null, null, null, null, null, null, Instant.EPOCH, null, null);

        var serialized = ConversationSerializer.serialize(List.of(assistant));

        assertThat(serialized)
            .as("thinking 不进摘要请求")
            .doesNotContain("internal reasoning")
            .doesNotContain("[Assistant thinking]")
            .contains("[Assistant]: answer")
            .contains("[Assistant tool calls]: read(path=\"a.txt\")");
    }

    @Test
    void userAndToolResultsKeepTheirExistingShape() {
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("do it")));
        var result = new Message.ToolResultMessage("call-1", "read",
            List.of(new ContentBlock.TextContent("file text")), false);

        var serialized = ConversationSerializer.serialize(List.of(user, result));

        assertThat(serialized).isEqualTo("[User]: do it\n\n[Tool result]: file text");
    }
}
