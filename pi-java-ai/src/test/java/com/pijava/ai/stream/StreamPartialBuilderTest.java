package com.pijava.ai.stream;

import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StreamPartialBuilderTest {

    @Test
    void interleavedTextAndToolBlocksDoNotOverwrite() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();

        builder.emitTextStart();
        builder.emitTextDelta("告诉");
        builder.emitToolCallStart("id1", "write");
        builder.emitToolCallDelta("id1", "{\"path\":\"hello.py\"");
        builder.emitTextDelta("我你想实现的功能");
        builder.emitToolCallDelta("id1", ",\"content\":\"print(\\\"hello\\\")\"}");
        builder.emitTextEnd();
        builder.emitToolCallEnd("id1", "write");

        var blocks = builder.snapshot().content();
        assertThat(blocks).hasSize(2);

        assertThat(blocks.get(0))
            .isInstanceOf(ContentBlock.TextContent.class);
        assertThat(((ContentBlock.TextContent) blocks.get(0)).text())
            .isEqualTo("告诉我你想实现的功能");

        assertThat(blocks.get(1))
            .isInstanceOf(ContentBlock.ToolUseContent.class);
        assertThat(((ContentBlock.ToolUseContent) blocks.get(1)).arguments())
            .containsEntry("path", "hello.py")
            .containsEntry("content", "print(\"hello\")");
    }

    @Test
    void emitUsageCarriesUsageInThePartialSnapshot() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        builder.emitTextStart();
        builder.emitTextDelta("hi");

        var usage = builder.emitUsage(123, 45);

        // ActionExecutor only counts tokens when the emitted UsageInfo's
        // partial snapshot carries the usage; regression for the status-bar
        // token counter staying at 0.
        assertThat(usage.inputTokens()).isEqualTo(123);
        assertThat(usage.outputTokens()).isEqualTo(45);
        assertThat(usage.partial()).isNotNull();
        assertThat(usage.partial().usage()).isNotNull();
        assertThat(usage.partial().usage().inputTokens()).isEqualTo(123);
        assertThat(usage.partial().usage().outputTokens()).isEqualTo(45);
    }

    @Test
    void emitUsageWithFullBreakdownCarriesEveryComponentToBothChannels() {
        // 包 H1 步 2（docs/42 §8.3）：`emitUsage(Usage)` 是加宽后的入口。
        // 两条通道都要拿到全量分解：事件自身的 `usage()` 与 partial 上的 `usage()`。
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        builder.emitTextStart();

        var full = new com.pijava.ai.Usage(1000, 250, 400, 80, 20.0, 12.0, 1730,
            new com.pijava.ai.Usage.Cost(0.003, 0.00375, 0.00012, 0.0004, 0.00727));
        var usage = builder.emitUsage(full);

        assertThat(usage.usage()).isEqualTo(full);
        assertThat(usage.partial()).isNotNull();
        assertThat(usage.partial().usage()).isNotNull();
        assertThat(usage.partial().usage().usage()).isEqualTo(full);

        // 计数通道仍从全量分解派生，供 PiLaneSink / SessionRunner 的累加器使用
        assertThat(usage.inputTokens()).isEqualTo(1000);
        assertThat(usage.outputTokens()).isEqualTo(250);
    }

    @Test
    void toUsagePrefersTheFullBreakdownOverTheSynthesizedCounts() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();

        var full = new com.pijava.ai.Usage(10, 20, 30, 40, null, null, 100,
            new com.pijava.ai.Usage.Cost(1, 2, 3, 4, 10));
        var usage = builder.emitUsage(full);

        assertThat(usage.toUsage()).isEqualTo(full);
        // 全量分解在场时，合成路径（cache 归零）不得被走到
        assertThat(usage.toUsage().cacheRead()).isEqualTo(30);
        assertThat(usage.toUsage().cost().total()).isEqualTo(10);
    }
    @Test
    void lenientMapperAcceptsModelJsonQuirks() throws Exception {
        // Trailing comma + unquoted field name, common in model-generated args.
        var parsed = StreamPartialBuilder.lenientMapper()
            .readValue("{command: \"echo hi\",}", java.util.Map.class);
        assertThat(parsed).isInstanceOf(java.util.Map.class);
    }

    @Test
    void signatureDeltaAccumulatesIntoThinkingBlock() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        builder.emitThinkingStart();
        builder.emitThinkingDelta("reasoning...");
        builder.emitThinkingSignature("sig_");
        builder.emitThinkingSignature("abc");

        var blocks = builder.snapshot().content();
        assertThat(blocks.get(0)).isInstanceOf(ContentBlock.ThinkingContent.class);
        var thinking = (ContentBlock.ThinkingContent) blocks.get(0);
        assertThat(thinking.text()).isEqualTo("reasoning...");
        assertThat(thinking.signature()).isEqualTo("sig_abc");
    }

    @Test
    void stopReasonStartsAtPendingAndOnlyTerminalEventsRewriteIt() {
        // ⑩（B26）：pi 五条车道的累加器都从 stopReason:"pending" 起、只在终局事件改写
        // （anthropic-messages.ts:526 / google-generative-ai.ts:75 /
        // mistral-conversations.ts:222 / openai-completions.ts:333 /
        // openai-responses.ts:139）⇒ 流进行中**每一帧**都是 "pending"。
        var builder = new StreamPartialBuilder();
        assertThat(builder.snapshot().stopReason()).isEqualTo("pending");
        assertThat(builder.emitStart().partial().stopReason()).isEqualTo("pending");
        assertThat(builder.emitTextStart().partial().stopReason()).isEqualTo("pending");
        assertThat(builder.emitTextDelta("hi").partial().stopReason()).isEqualTo("pending");

        // 终局事件是唯一的改写点（这里是车道映射后的取值）。
        assertThat(builder.emitDone("length").partial().stopReason()).isEqualTo("length");
    }
}
