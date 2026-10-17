package com.pijava.agent.harness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.agent.compaction.SummaryGenerator.SummaryResult;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #98/B36：per-operation 压缩取消 —— pi {@code abortCompaction()}
 * （agent-session.ts:2914-2917）对位：手动/自动双槽，同点生效（生成后落库前，
 * :2843-2845），转录不动。
 */
class CompactionAbortTokenTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "abort-model");
    private static final CompactionSettings SETTINGS = new CompactionSettings(true, 16_384, 1);

    private static Entry.Message msg(String parentId, String text) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, parentId, Instant.now(),
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    private static Entry.Message assistantMsg(String parentId, String text) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, parentId, Instant.now(),
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    @Test
    void abortCompactionCancelsTheInFlightManualCompaction() {
        StreamFn neverCalled = (model, context, options) -> {
            throw new AssertionError("streamFn 不该被调用");
        };
        // 生成器要回调 harness ⇒ 自引用晚绑定（生成必晚于 create 返回）。
        var self = new java.util.concurrent.atomic.AtomicReference<AgentHarness>();
        var config = HarnessConfig.builder()
            .streamFn(neverCalled)
            .model(MODEL)
            .maxInputTokens(200_000)
            .contextWindow(ignored -> 200_000)
            .summaryGenerator(new SummaryGenerator() {
                @Override
                public SummaryResult summarize(List<Message> compressed, String previousSummary,
                                               String customInstructions, int reserveTokens,
                                               String reason) {
                    self.get().abortCompaction(AgentHarness.DEFAULT_LANE);
                    return new SummaryResult("SHOULD-NOT-LAND", null);
                }

                @Override
                public SummaryResult summarizeTurnPrefix(List<Message> messages, int reserveTokens,
                                                         String reason) {
                    self.get().abortCompaction(AgentHarness.DEFAULT_LANE);
                    return new SummaryResult("SHOULD-NOT-LAND", null);
                }
            })
            .build();
        try (var h = AgentHarness.create(config)) {
            self.set(h);
            // 压缩切点扫 parentId 链（投影沿叶子路径）—— 链式播种，否则只见末条。
            var first = msg(null, "one");
            var second = assistantMsg(first.id(), "reply");
            var third = msg(second.id(), "three");
            h.seedTranscript(AgentHarness.DEFAULT_LANE, List.of(first, second, third));

            assertThatThrownBy(() -> h.compact(AgentHarness.DEFAULT_LANE, SETTINGS, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Compaction cancelled");
            // pi :2843-2845：落库前中止 ⇒ 转录未动。
            assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).transcript()).hasSize(3);
            // 窗口与令牌都已清位（pi :2744-2747）。
            assertThat(h.isCompacting(AgentHarness.DEFAULT_LANE)).isFalse();
        }
    }

    @Test
    void abortCompactionWithoutInFlightCompactionIsANoOp() {
        // pi 的 `?.abort()`：无控制器 ⇒ 什么都不发生，不抛。
        StreamFn neverCalled = (model, context, options) -> {
            throw new AssertionError("streamFn 不该被调用");
        };
        var config = HarnessConfig.builder()
            .streamFn(neverCalled)
            .model(MODEL)
            .maxInputTokens(200_000)
            .contextWindow(ignored -> 200_000)
            .build();
        try (var h = AgentHarness.create(config)) {
            h.abortCompaction(AgentHarness.DEFAULT_LANE);
            assertThat(h.isCompacting(AgentHarness.DEFAULT_LANE)).isFalse();
        }
    }
}
