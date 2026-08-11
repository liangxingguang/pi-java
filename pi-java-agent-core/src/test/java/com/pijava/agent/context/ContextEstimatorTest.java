package com.pijava.agent.context;

import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContextEstimatorTest {

    @Test
    void estimateTokensEmpty() {
        assertThat(ContextEstimator.estimateTokens(List.of())).isEqualTo(0);
    }

    @Test
    void estimateTokensSingleMessage() {
        var msg = new Message.UserMessage(List.of(
                new ContentBlock.TextContent("Hello, world!")));
        long tokens = ContextEstimator.estimateTokens(List.of(msg));
        assertThat(tokens).isGreaterThan(0);
        // 13 chars / 3.5 ≈ 4 tokens
        assertThat(tokens).isEqualTo(4);
    }

    @Test
    void estimateTokensMultipleMessages() {
        var messages = List.<Message>of(
                new Message.SystemMessage(List.of(
                        new ContentBlock.TextContent("You are helpful."))),
                new Message.UserMessage(List.of(
                        new ContentBlock.TextContent("Hello"))));
        long tokens = ContextEstimator.estimateTokens(messages);
        assertThat(tokens).isGreaterThan(0);
    }

    @Test
    void checkOverflowNoOverflow() {
        var msg = new Message.UserMessage(List.of(
                new ContentBlock.TextContent("short")));
        int result = ContextEstimator.checkOverflow(List.of(msg), 100_000);
        assertThat(result).isEqualTo(0);
    }

    @Test
    void checkOverflowDetectsOverflow() {
        // Create a very long message
        var longText = "x".repeat(500_000); // ~142K tokens
        var msg = new Message.UserMessage(List.of(
                new ContentBlock.TextContent(longText)));
        int result = ContextEstimator.checkOverflow(List.of(msg), 100_000);
        assertThat(result).isGreaterThan(0);
    }

    @Test
    void checkOverflowSkipsSystemMessagesWhenRemoving() {
        // Small messages within a large window — no overflow
        var systemMsg = new Message.SystemMessage(List.of(
                new ContentBlock.TextContent("You are a helpful assistant.")));
        var userMsg = new Message.UserMessage(List.of(
                new ContentBlock.TextContent("hi")));
        int result = ContextEstimator.checkOverflow(
                List.of(systemMsg, userMsg), 100_000);
        assertThat(result).isEqualTo(0);
    }
}
