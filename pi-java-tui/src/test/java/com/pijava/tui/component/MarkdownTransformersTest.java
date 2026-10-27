package com.pijava.tui.component;

import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.MarkdownTransformContext.MarkdownMessageType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 26（docs/37 T11，E5-9＋map/05 R6）：markdown 变换链（pi
 * markdown-transform.ts:16-28 语义逐字——串行、异常吞、null 丢弃）与
 * MessageBubble 两臂接缝。
 */
class MarkdownTransformersTest {

    @AfterEach
    void tearDown() {
        MarkdownTransformers.install(null);
    }

    @Test
    void chainFeedsOutputToNext() {
        MarkdownTransformers.install(() -> List.of(
            (md, ctx) -> md + "-a",
            (md, ctx) -> md + "-b"));

        assertThat(MarkdownTransformers.apply("x", MarkdownMessageType.ASSISTANT))
            .isEqualTo("x-a-b");
    }

    @Test
    void exceptionKeepsCurrentAndContinues() {
        MarkdownTransformers.install(() -> List.of(
            (md, ctx) -> {
                throw new RuntimeException("boom");
            },
            (md, ctx) -> md + "!"));

        assertThat(MarkdownTransformers.apply("x", MarkdownMessageType.ASSISTANT))
            .isEqualTo("x!");
    }

    @Test
    void nullReturnDiscardsThatResult() {
        MarkdownTransformers.install(() -> List.of(
            (md, ctx) -> null,
            (md, ctx) -> md + "!"));

        assertThat(MarkdownTransformers.apply("x", MarkdownMessageType.ASSISTANT))
            .isEqualTo("x!");
    }

    @Test
    void emptyChainPassThrough() {
        MarkdownTransformers.install(List::of);
        assertThat(MarkdownTransformers.apply("x", MarkdownMessageType.ASSISTANT))
            .isEqualTo("x");
    }

    @Test
    void assistantArmAppliesTransformer() {
        MarkdownTransformers.install(() -> List.of((md, ctx) -> {
            // context 三参钉：型真实、流式恒 false、宽恒 -1（裁决④）。
            assertThat(ctx.messageType()).isEqualTo(MarkdownMessageType.ASSISTANT);
            assertThat(ctx.isStreaming()).isFalse();
            assertThat(ctx.availableWidth()).isEqualTo(-1);
            return md.replace("hello", "goodbye");
        }));

        var lines = MessageBubble.lines(new ChatMessage.Assistant(
            List.of(new ContentBlock.TextContent("hello world"))));

        var joined = String.join("\n", lines.stream().map(LogicalLine::markup).toList());
        assertThat(joined).contains("goodbye world");
        assertThat(joined).doesNotContain("hello");
    }

    @Test
    void thinkingArmUsesThinkingMessageType() {
        var seen = new java.util.concurrent.atomic.AtomicReference<MarkdownMessageType>();
        MarkdownTransformers.install(() -> List.of((md, ctx) -> {
            seen.set(ctx.messageType());
            return md;
        }));

        MessageBubble.lines(new ChatMessage.Assistant(
            List.of(new ContentBlock.ThinkingContent("ponder", null, false))));

        assertThat(seen.get()).isEqualTo(MarkdownMessageType.ASSISTANT_THINKING);
    }
}
