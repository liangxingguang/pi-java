package com.pijava.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * 批 13（docs/24 T4）：cache-warmer 的两个 harness 缝——
 * {@link AgentHarness#getMessages}（pi sdk.ts:370-377 isCurrent 的活视图操作数）与
 * {@link AgentHarness#appendDeferredLaneEntry}（pi {@code SessionManager._appendEntry}
 * 的双态对位：run 中记 deferred write、idle 纯追加）。
 */
class AgentHarnessCacheWarmingSeamsTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static StreamFn textStreamFn(String text) {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason(StopReason.STOP);
        return (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.StreamDone(StopReason.STOP, null, partial)
        ));
    }

    private static AgentHarness createHarness(StreamFn streamFn) {
        return AgentHarness.create(HarnessConfig.builder()
                .streamFn(streamFn)
                .model(MODEL)
                .thinkingLevel(ModelThinkingLevel.off())
                .systemPrompt("")
                .activeTools(Set.of())
                .maxInputTokens(200_000)
                .telemetry(com.pijava.telemetry.NoopTelemetryContext.INSTANCE)
                .thinkingLevelMap(com.pijava.ai.thinking.ThinkingLevelMap.empty())
                .build());
    }

    private static Entry.Usage usageEntry(long seq, String parentId) {
        return new Entry.Usage(java.util.UUID.randomUUID().toString(), seq, parentId,
            Instant.now(), "cache_warm", "faux", "test-model",
            Usage.of(100, 1, 101), null, null, null, null, null, null);
    }

    /** 活视图：run 推进后同一份视图随之增长；元素身份稳定（前缀恒等比较的操作数）。 */
    @Test
    void getMessagesReturnsLiveViewOfWorkingMessages() {
        var harness = createHarness(textStreamFn("one"));
        var view = harness.getMessages();
        assertThat(view).isEmpty();

        harness.prompt("first");
        var sizeAfterFirst = view.size();
        assertThat(sizeAfterFirst).isGreaterThan(0);
        var firstElements = List.copyOf(view);

        harness.prompt("second");
        assertThat(view.size()).isGreaterThan(sizeAfterFirst);
        // 前缀恒等：引擎只追加不替换前缀（isCurrent 的成立条件）。
        for (int i = 0; i < firstElements.size(); i++) {
            assertThat(view.get(i)).isSameAs(firstElements.get(i));
        }
    }

    /** 修改抛 UOE（读口不是写口）。 */
    @Test
    void getMessagesViewIsUnmodifiable() {
        var harness = createHarness(textStreamFn("x"));
        harness.prompt("go");
        var view = harness.getMessages();
        assertThatThrownBy(view::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    /** idle 臂：纯追加——seq/parentId 在锁下供给，条目进 transcript。 */
    @Test
    void appendDeferredLaneEntryOnIdleLaneAppendsWithLaneIdentity() {
        var harness = createHarness(textStreamFn("x"));
        var lane = harness.laneName();

        var first = harness.appendDeferredLaneEntry(lane, AgentHarnessCacheWarmingSeamsTest::usageEntry);
        assertThat(first.seq()).isZero();
        assertThat(first.parentId()).isNull();

        var second = harness.appendDeferredLaneEntry(lane, AgentHarnessCacheWarmingSeamsTest::usageEntry);
        assertThat(second.seq()).isEqualTo(1);
        assertThat(second.parentId()).isEqualTo(first.id());

        assertThat(harness.snapshot(lane).transcript()).containsExactly(first, second);
    }

    /** run 中臂：不抛（{@code appendLaneEntry} 在运行中会 ISE），记 deferred write，条目落 transcript。 */
    @Test
    void appendDeferredLaneEntryDuringRunRecordsDeferredWrite() {
        var harnessRef = new AtomicReference<AgentHarness>();
        // 在流起手时（run 已活）追加——模拟 warmer 调度线程的 appendUsage 收口。
        StreamFn duringRun = (model, context, options) -> {
            harnessRef.get().appendDeferredLaneEntry(harnessRef.get().laneName(),
                AgentHarnessCacheWarmingSeamsTest::usageEntry);
            var partial = AssistantMessage.empty()
                    .withContent(List.of(new ContentBlock.TextContent("mid")))
                    .withStopReason(StopReason.STOP);
            return StreamIterator.from(List.of(
                    new StreamEvent.Start(AssistantMessage.empty()),
                    new StreamEvent.StreamDone(StopReason.STOP, null, partial)
            ));
        };
        var harness = createHarness(duringRun);
        harnessRef.set(harness);
        harness.prompt("go");

        var transcript = harness.snapshot(harness.laneName()).transcript();
        assertThat(transcript).anyMatch(entry -> entry instanceof Entry.Usage usage
            && "cache_warm".equals(usage.kind()));
    }
}
