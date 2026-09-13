package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个 harness 恰好一条车道（{@code docs/31 §4.3}）。
 *
 * <p>运行时多车道容器（{@code LaneRegistry} / {@code LaneHandle} / {@code LaneConfig} /
 * {@code createLane} / {@code lanes()} / {@code moveLane}）已删除：pi 的对齐目标
 * {@code agent.ts} 是**单状态**的，分支归会话层 —— 每个 {@code AgentSession} 持有自己的
 * harness（{@link AgentHarness#fork()}），存储层的 lane 才是 pi 的分支模型。</p>
 *
 * <p>本类取代原先的 {@code MultiLaneTest}：它钉住的不再是「容器怎么用」，而是
 * **容器不在了**之后仍然成立的三条 —— 一条车道、名字对不上就炸、两个 harness 互不相干。</p>
 */
class SingleLaneTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("ok")))
                .withStopReason("stop");
        StreamFn sf = (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "ok", partial),
                new StreamEvent.StreamDone("stop", null, partial)));
        return AgentHarness.create(new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
    }

    @Test
    void aHarnessHasExactlyOneLane() {
        var h = harness();
        assertThat(h.laneName()).isEqualTo(AgentHarness.DEFAULT_LANE);
    }

    /**
     * 车道名对不上就是「没有这条车道」—— 以前 {@code createLane} 会给你建一条，
     * 现在只能炸。它防的是调用方还拿着旧的分支名（那些分支已归会话层）。
     */
    @Test
    void anUnknownLaneNameIsRejected() {
        var h = harness();
        assertThatThrownBy(() -> h.prompt("review", "hi", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Lane not found: review");
        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).transcript()).isEmpty();
    }

    /** 同一条车道上连续运行**累加**在同一个日志里（一条车道就是一个上下文）。 */
    @Test
    void consecutiveRunsAccumulateInOneTranscript() {
        var h = harness();
        h.prompt("first");
        int afterFirst = h.snapshot(AgentHarness.DEFAULT_LANE).transcript().size();
        h.prompt("second");
        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).transcript().size())
            .isGreaterThan(afterFirst);
    }

    /**
     * {@link AgentHarness#fork()} 给会话分支一个**独立**宿主：空日志，且父 harness 的运行
     * 不会漏进去（这正是删除容器之前「子会话共用父 harness、靠新建 lane 假装隔离」的反面）。
     */
    @Test
    void forkReturnsAnIndependentEmptyHarness() {
        var parent = harness();
        var forked = parent.fork();

        assertThat(forked.laneName()).isEqualTo(AgentHarness.DEFAULT_LANE);
        assertThat(forked.snapshot(AgentHarness.DEFAULT_LANE).transcript()).isEmpty();

        parent.prompt("parent only");
        assertThat(forked.snapshot(AgentHarness.DEFAULT_LANE).transcript())
            .as("父 harness 的运行不得漏进分支")
            .isEmpty();

        forked.prompt("branch only");
        assertThat(forked.snapshot(AgentHarness.DEFAULT_LANE).transcript()).isNotEmpty();
        assertThat(parent.snapshot(AgentHarness.DEFAULT_LANE).transcript())
            .as("分支的运行也不得漏回父 harness")
            .hasSizeGreaterThan(0);
        assertThat(forked.snapshot(AgentHarness.DEFAULT_LANE).transcript())
            .isNotEqualTo(parent.snapshot(AgentHarness.DEFAULT_LANE).transcript());
    }
}
