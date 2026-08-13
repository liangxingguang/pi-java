package com.pijava.tui.component;

import java.util.List;

import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: every ChatMessage variant renders without exception.
 */
class MessageBubbleTest {

    @Test
    void rendersAllVariants() {
        assertThat(MessageBubble.of(new ChatMessage.User("hello"))).isNotNull();
        assertThat(MessageBubble.of(new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("reply"))))).isNotNull();
        assertThat(MessageBubble.of(new ChatMessage.ToolCall("read", "{}")))
            .isNotNull();
        assertThat(MessageBubble.of(new ChatMessage.ToolResult("output")))
            .isNotNull();
        assertThat(MessageBubble.of(new ChatMessage.Error("boom"))).isNotNull();
        assertThat(MessageBubble.of(new ChatMessage.System("info"))).isNotNull();
    }

    @Test
    void chatMessageProjectsToolEntry() {
        var entry = new com.pijava.agent.entry.Entry.Message(
            com.pijava.agent.entry.Entry.newHeader(0, ""), "tool",
            List.of(new ContentBlock.ToolResultContent(
                "id", "read", List.of(new ContentBlock.TextContent("file")), false)));

        var message = ChatMessage.from(entry);
        assertThat(message).isInstanceOf(ChatMessage.ToolResult.class);
    }
}
