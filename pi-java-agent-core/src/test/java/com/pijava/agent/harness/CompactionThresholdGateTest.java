package com.pijava.agent.harness;

import java.time.Instant;
import java.util.List;
import java.util.function.ToIntFunction;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.HookSystem;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.telemetry.NoopTelemetryContext;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * package 3b 的阈值门判据（pi {@code _compactBeforeNextAssistantResponse}，
 * {@code agent-session.ts:542-557} + {@code _runAutoCompaction} 内
 * {@code prepareCompaction} 的 :638 守卫；{@code docs/31 §8.20}）。
 *
 * <p>直接搭 {@link CompactionExecutor}（同包可见），把门的每一条形状各钉一
 * 颗哨兵：设置缺席、**当前模型缺席**、**窗口 ≤ 0**、估算不过线、
 * 「末条已是 compaction ⇒ 静默不压」，以及两条真发火的路（用量驱动 /
 * 字符驱动）。窗口操作数按模型解析 —— 这是 3b 修掉的核心分歧（此前恒读
 * 静态 {@code maxInputTokens}）。</p>
 */
class CompactionThresholdGateTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "gate-model");
    private static final CompactionSettings SETTINGS = new CompactionSettings(true, 10, 20_000);

    /** 只填门与 applyCompaction 实际会读的槽位，其余置 null。 */
    private static ExecutionContext ctx(LaneState lane, CompactionSettings settings,
                                        ModelId<?> model, ToIntFunction<ModelId<?>> window) {
        return new ExecutionContext(
            null, () -> model, null, null, null,
            200_000, window, null,
            null, null, null,
            new HookSystem(lane), lane, () -> settings, null,
            new ExecutionContext.TokenCounter(), null, null, null, null,
            SummaryGenerator.truncating(), null, NoopTelemetryContext.INSTANCE, null,
            null, null, null);
    }

    private static Entry messageEntry(String id, Message message) {
        return new Entry.Message(id, 0, null, Instant.ofEpochMilli(1), message, false);
    }

    private static Message.AssistantMessage withUsage(int totalTokens) {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("ok")), "stop", null,
            null, null, null,
            new Usage(totalTokens, 0, 0, 0, null, null, totalTokens, Usage.Cost.zero()),
            null, null, null);
    }

    private static Entry.Compaction marker(String id) {
        return new Entry.Compaction(id, 0, null, Instant.ofEpochMilli(1),
            "old summary", "e1", List.of(), 10, null, null);
    }

    /** 一条「巨大用量」的车道：字符估算不过线，用量估算远超（阈值 = 200-10）。 */
    private static LaneState usageHotLane() {
        var lane = new LaneState();
        var msg = withUsage(500);
        lane.transcript.add(messageEntry("e1", msg));
        lane.messages.add(msg);
        return lane;
    }

    private static boolean compacted(LaneState lane) {
        return lane.transcript.stream().anyMatch(Entry.Compaction.class::isInstance);
    }

    @Test
    void missingSettingsSkips() {
        var lane = usageHotLane();
        var executor = new CompactionExecutor(ctx(lane, null, MODEL, id -> 200));
        assertThat(executor.checkThreshold("default", lane)).isFalse();
    }

    @Test
    void missingModelSkips_piGuard547() {
        var lane = usageHotLane();
        var executor = new CompactionExecutor(ctx(lane, SETTINGS, null, id -> 200));
        assertThat(executor.checkThreshold("default", lane)).isFalse();
        assertThat(compacted(lane)).isFalse();
    }

    @Test
    void nonPositiveContextWindowSkips_piGuard548() {
        // 自定义模型不在目录 ⇒ 宿主解析出 0 ⇒ pi 的 `contextWindow <= 0` 守卫
        // 跳过自动压缩，而不是拿 0 当窗口把一切都压掉。
        var lane = usageHotLane();
        var executor = new CompactionExecutor(ctx(lane, SETTINGS, MODEL, id -> 0));
        assertThat(executor.checkThreshold("default", lane)).isFalse();
        assertThat(compacted(lane)).isFalse();
    }

    @Test
    void estimateBelowThresholdSkips() {
        var lane = usageHotLane();
        var executor = new CompactionExecutor(ctx(lane, SETTINGS, MODEL, id -> 1_000_000));
        assertThat(executor.checkThreshold("default", lane)).isFalse();
        assertThat(compacted(lane)).isFalse();
    }

    @Test
    void lastEntryIsCompactionSkipsSilently_prepare638() {
        // pi 的「别连着压两次」守卫住在 prepareCompaction（:638）：末条已是
        // compaction ⇒ **静默**返回 false（不抛）。旧实现用
        // transcript.size() <= 1 门槛顶替它，是发明 —— 3b 撤下。
        var lane = new LaneState();
        lane.transcript.add(marker("c1"));
        lane.messages.add(withUsage(500));
        var executor = new CompactionExecutor(ctx(lane, SETTINGS, MODEL, id -> 200));
        assertThat(executor.checkThreshold("default", lane)).isFalse();
        assertThat(lane.transcript).hasSize(1);
        assertThat(lane.records).isEmpty();
    }

    @Test
    void usageBackedEstimateFiresEvenWhenCharsWouldNot() {
        // 门发火 + 落库的 tokensBefore 与判据同源（usage=500 写进 marker）。
        var lane = usageHotLane();
        var executor = new CompactionExecutor(ctx(lane, SETTINGS, MODEL, id -> 200));
        assertThat(executor.checkThreshold("default", lane)).isTrue();
        assertThat(lane.transcript.getFirst()).isInstanceOf(Entry.Compaction.class);
        var marker = (Entry.Compaction) lane.transcript.getFirst();
        assertThat(marker.tokensBefore()).isEqualTo(500);
    }

    @Test
    void charBackedEstimateFiresWithNoUsageAnywhere() {
        // 无 usage（旧世界/纯字符路）：4000 字符 ⇒ ceil(4000/4)=1000 > 190。
        var lane = new LaneState();
        var msg = new Message.UserMessage(List.of(
            new ContentBlock.TextContent("x".repeat(4_000))));
        lane.transcript.add(messageEntry("e1", msg));
        lane.messages.add(msg);
        var executor = new CompactionExecutor(ctx(lane, SETTINGS, MODEL, id -> 200));
        assertThat(executor.checkThreshold("default", lane)).isTrue();
        assertThat(((Entry.Compaction) lane.transcript.getFirst()).tokensBefore())
            .isEqualTo(1_000);
    }

    @Test
    void windowOperandFollowsCurrentModel() {
        // 操作数是**当前模型**的窗口：同一车道，模型 A（200）压、模型 B（百万）不压。
        var laneA = usageHotLane();
        var laneB = usageHotLane();
        ToIntFunction<ModelId<?>> perModel = id ->
            "small".equals(id.modelName()) ? 200 : 1_000_000;
        assertThat(new CompactionExecutor(ctx(laneA, SETTINGS,
            ModelId.of("faux", "small"), perModel)).checkThreshold("default", laneA)).isTrue();
        assertThat(new CompactionExecutor(ctx(laneB, SETTINGS,
            ModelId.of("faux", "big"), perModel)).checkThreshold("default", laneB)).isFalse();
    }

    @Test
    void disabledSettingsNeverFire() {
        var lane = usageHotLane();
        var executor = new CompactionExecutor(ctx(lane,
            new CompactionSettings(false, 10, 20_000), MODEL, id -> 200));
        assertThat(executor.checkThreshold("default", lane)).isFalse();
    }
}
