package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置 entry 由**设置点**写入（{@code docs/31 §4.1}）。
 *
 * <p>pi 的形状是「字段赋值与 entry 追加同处、不走事件」
 * （{@code agent-session.ts:1687} 的 {@code setModel}、{@code :1813-1829} 的
 * {@code setThinkingLevel}）。本类咬住三件事：</p>
 *
 * <ul>
 *   <li>setter 写 entry（此前只改字段 ⇒ 切换后持久化日志丢失该变更）；</li>
 *   <li>思考等级**只在变更时**写，且默认（off）不写 —— pi 的 {@code isChanging} 守卫；</li>
 *   <li>重复运行 / 恢复都不重复写 —— 靠 {@code LaneState.recordedThinking}。</li>
 * </ul>
 */
class ConfigEntryEmissionTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static final ModelThinkingLevel HIGH =
        ModelThinkingLevel.of(new ThinkingLevel.High());

    private static StreamFn textStreamFn(String text) {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason("stop");
        return (model, context, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, partial.withContent(
                List.of(new ContentBlock.TextContent("")))),
            new StreamEvent.TextDelta(0, text, partial.withStopReason(null)),
            new StreamEvent.TextEnd(0, text, partial.withStopReason(null)),
            new StreamEvent.StreamDone("stop", null, partial)
        ));
    }

    private static AgentHarness createHarness(ModelThinkingLevel level) {
        return AgentHarness.create(new HarnessConfig(
            textStreamFn("ok"), MODEL, level, "",
            Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(), com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    private static List<Entry.ThinkingLevelChange> thinkingChanges(AgentHarness harness) {
        return harness.snapshot(AgentHarness.DEFAULT_LANE).transcript().stream()
            .filter(Entry.ThinkingLevelChange.class::isInstance)
            .map(Entry.ThinkingLevelChange.class::cast)
            .toList();
    }

    private static List<Entry.ModelChange> modelChanges(AgentHarness harness) {
        return harness.snapshot(AgentHarness.DEFAULT_LANE).transcript().stream()
            .filter(Entry.ModelChange.class::isInstance)
            .map(Entry.ModelChange.class::cast)
            .toList();
    }

    // ── model ────────────────────────────────────────────────

    /**
     * 切换模型必须留痕 —— 这是本次修复的洞：{@code /model}、TUI 模型选择器走
     * {@code setModel}，此前只改字段，恢复时模型丢失。
     */
    @Test
    void setModelAppendsAModelChangeEntry() {
        var harness = createHarness(ModelThinkingLevel.off());
        assertThat(modelChanges(harness)).isEmpty();

        harness.setModel(ModelId.of("other-provider", "other-model"));

        var changes = modelChanges(harness);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).provider()).isEqualTo("other-provider");
        assertThat(changes.get(0).modelId()).isEqualTo("other-model");
    }

    // ── thinking level ───────────────────────────────────────

    @Test
    void setThinkingLevelAppendsOnlyWhenTheLevelActuallyChanges() {
        var harness = createHarness(ModelThinkingLevel.off());

        harness.setThinkingLevel(HIGH);
        assertThat(thinkingChanges(harness)).hasSize(1);

        harness.setThinkingLevel(HIGH);          // 无变更 ⇒ 不重复写
        assertThat(thinkingChanges(harness)).hasSize(1);

        harness.setThinkingLevel(ModelThinkingLevel.off());
        assertThat(thinkingChanges(harness)).hasSize(1);   // off 不入日志（pi 只在非默认时写）

        harness.setThinkingLevel(ModelThinkingLevel.of(new ThinkingLevel.Low()));
        assertThat(thinkingChanges(harness)).hasSize(2);
    }

    /**
     * 首次运行补记一次，之后同一等级不再重复 —— 原先每次运行都无条件追加一条，
     * 日志里会堆出一串同样的 {@code ThinkingLevelChange}。
     */
    @Test
    void repeatedRunsDoNotReStampTheSameThinkingLevel() {
        var harness = createHarness(HIGH);

        harness.prompt("first");
        assertThat(thinkingChanges(harness)).hasSize(1);
        assertThat(thinkingChanges(harness).get(0).thinkingLevel()).isEqualTo("high");

        harness.prompt("second");
        harness.prompt("third");
        assertThat(thinkingChanges(harness)).hasSize(1);
    }

    /** 默认等级（off）不进日志 —— 与 pi 的「只在非默认时 append」一致。 */
    @Test
    void runWithTheDefaultThinkingLevelWritesNoEntry() {
        var harness = createHarness(ModelThinkingLevel.off());
        harness.prompt("hello");
        assertThat(thinkingChanges(harness)).isEmpty();
    }

    /**
     * 恢复时「上次记过什么」必须跟着既有日志走，否则恢复后的第一次运行会把一条
     * 已经在日志里的 entry 再写一遍。
     */
    @Test
    void seedingATranscriptDoesNotCauseADuplicateOnTheNextRun() {
        var harness = createHarness(HIGH);
        harness.prompt("before the crash");
        assertThat(thinkingChanges(harness)).hasSize(1);

        var resumed = createHarness(HIGH);
        resumed.seedTranscript(AgentHarness.DEFAULT_LANE,
            harness.snapshot(AgentHarness.DEFAULT_LANE).transcript());
        assertThat(thinkingChanges(resumed)).hasSize(1);   // 播种前是空的，播种后才有一条

        resumed.setThinkingLevel(HIGH);                    // 与日志里的一致 ⇒ 不补写
        assertThat(thinkingChanges(resumed)).hasSize(1);

        resumed.prompt("after resume");
        assertThat(thinkingChanges(resumed)).hasSize(1);
    }
}
