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
                new Message.UserMessage(List.of(
                        new ContentBlock.TextContent("Hello"))),
                new Message.AssistantMessage(List.of(
                        new ContentBlock.TextContent("Hi!"))));
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

    /**
     * {@code checkOverflow} 不再有「跳过 system 消息」这条豁免：消息列表里**没有**
     * system 角色（pi 的 {@code Message} 只有 user/assistant/toolResult）——
     * 系统提示是 {@code Context.systemPrompt}，根本不进列表，因此所有消息都是压缩候选。
     */
    @Test
    void checkOverflowTreatsEveryMessageAsRemovable() {
        var longText = "y".repeat(500_000); // ~142K tokens
        var messages = List.<Message>of(
                new Message.UserMessage(List.of(
                        new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(
                        new ContentBlock.TextContent(longText))));
        int result = ContextEstimator.checkOverflow(messages, 100_000);
        assertThat(result).as("含超长消息 ⇒ 溢出，且最早的那条也算候选").isGreaterThan(0);
    }
}
