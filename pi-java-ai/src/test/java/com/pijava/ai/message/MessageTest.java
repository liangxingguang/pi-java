package com.pijava.ai.message;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link Message} sealed hierarchy and {@link ContentBlock} subtypes.
 */
class MessageTest {

    @Test
    void systemMessageShouldHoldText() {
        var msg = new Message.SystemMessage(
                List.of(new ContentBlock.TextContent("You are a helpful assistant.")));

        assertThat(msg.content()).hasSize(1);
        assertThat(msg.content().get(0)).isInstanceOf(ContentBlock.TextContent.class);
        assertThat(((ContentBlock.TextContent) msg.content().get(0)).text())
                .isEqualTo("You are a helpful assistant.");
    }

    @Test
    void userMessageShouldHoldMultipleBlocks() {
        var msg = new Message.UserMessage(List.of(
                new ContentBlock.TextContent("Hello"),
                new ContentBlock.ImageContent("image/png", "base64data")));

        assertThat(msg.content()).hasSize(2);
        assertThat(msg.content().get(0)).isInstanceOf(ContentBlock.TextContent.class);
        assertThat(msg.content().get(1)).isInstanceOf(ContentBlock.ImageContent.class);
    }

    @Test
    void assistantMessageShouldHoldToolUse() {
        var msg = new Message.AssistantMessage(List.of(
                new ContentBlock.ToolUseContent("toolu_01",
                        "read", Map.of("path", "/src/main.java"))));

        assertThat(msg.content()).hasSize(1);
        var block = (ContentBlock.ToolUseContent) msg.content().get(0);
        assertThat(block.id()).isEqualTo("toolu_01");
        assertThat(block.name()).isEqualTo("read");
        assertThat(block.arguments()).containsEntry("path", "/src/main.java");
    }

    @Test
    void toolResultShouldPreserveErrorFlag() {
        var msg = new Message.UserMessage(List.of(
                new ContentBlock.ToolResultContent("toolu_01", "File not found", true)));

        assertThat(msg.content()).hasSize(1);
        var block = (ContentBlock.ToolResultContent) msg.content().get(0);
        assertThat(block.toolUseId()).isEqualTo("toolu_01");
        assertThat(block.content()).isEqualTo("File not found");
        assertThat(block.isError()).isTrue();
    }

    @Test
    void textContentEquality() {
        var a = new ContentBlock.TextContent("hello");
        var b = new ContentBlock.TextContent("hello");
        var c = new ContentBlock.TextContent("world");

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toolUseContentDefensiveCopy() {
        var args = new java.util.HashMap<String, Object>();
        args.put("key", "value");
        var block = new ContentBlock.ToolUseContent("id", "tool", args);

        args.put("key", "modified");
        assertThat(block.arguments()).containsEntry("key", "value");
    }
}
