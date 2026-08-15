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
    void wrapKeepsShortAndMultiLineTextIntact() {
        assertThat(MessageBubble.wrap("short", 40)).isEqualTo("short");
        assertThat(MessageBubble.wrap("one\ntwo\nthree", 40))
            .isEqualTo("one\ntwo\nthree");
        assertThat(MessageBubble.wrap(null, 40)).isNull();
        assertThat(MessageBubble.wrap("", 40)).isEmpty();
    }

    @Test
    void wrapBreaksLongLinesAtWordBoundaries() {
        var text = "aaaa bbbb cccc dddd eeee";
        assertThat(MessageBubble.wrap(text, 10))
            .isEqualTo("aaaa bbbb\ncccc dddd\neeee");
    }

    @Test
    void wrapHardBreaksWordsLongerThanTheWidth() {
        assertThat(MessageBubble.wrap("abcdefghij", 4))
            .isEqualTo("abcd\nefgh\nij");
    }

    @Test
    void wrapCountsWideCharactersAsTwoColumns() {
        // 4 CJK chars = 8 display columns, so a width of 6 must break after 3.
        assertThat(MessageBubble.wrap("你好世界", 6)).isEqualTo("你好世\n界");
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
