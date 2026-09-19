package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.pijava.agent.compaction.CompactionObserver;
import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.HookSystem;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.telemetry.NoopTelemetryContext;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 包④：压缩的「在飞窗口」（pi {@code AgentSession.isCompacting}，
 * {@code agent-session.ts:983-990}；{@code docs/31 §8.37}）。
 *
 * <p><b>置位点两个入口不同</b>，这是本文件要钉的核心 —— pi 原文：
 * 手动 {@code compact()} 先建控制器<b>再</b>发事件（{@code :1969} 在 {@code :1970} 之前），
 * 自动 {@code _runAutoCompaction()} 先发事件<b>再</b>建控制器（{@code :2290} 在
 * {@code :2291} 之前）。两条的清位都在 {@code finally}（{@code :2117}/{@code :2450}）。
 * 所以观察者在 {@code onStart} 里采样到的值<b>恰好相反</b>：手动 {@code true}、
 * 自动 {@code false} —— 把它当「观察者窗口 = 在飞窗口」写成同一条断言就会红，
 * 而红的是夹具、不是实现（本文件第一版正是这么写错的）。</p>
 *
 * <p>手动路走<b>真 harness</b>（顺带钉住 {@link AgentHarness#isCompacting(String)}
 * 的转发）；自动路无法在不起一整轮 prompt 的前提下从 harness 驱动，按包内既有
 * 惯例直接搭 {@link CompactionExecutor}（同 {@code CompactionThresholdGateTest}）。</p>
 */
class CompactionInFlightTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "inflight-model");
    private static final CompactionSettings SETTINGS = new CompactionSettings(true, 10, 20_000);

    /** 在压缩窗口内采样 {@code isCompacting} 的观察者。 */
    private static final class Sampling implements CompactionObserver {
        /** 采样探针 —— 由测试在 harness 建好后注入（harness 与 observer 互相引用）。 */
        BooleanSupplier probe = () -> false;
        final List<Boolean> onStart = new ArrayList<>();
        final List<Boolean> onEnd = new ArrayList<>();

        @Override
        public void onStart(String reason) {
            onStart.add(probe.getAsBoolean());
        }

        @Override
        public void onEnd(String reason, CompactionResult result,
                          boolean aborted, boolean willRetry, String errorMessage) {
            onEnd.add(probe.getAsBoolean());
        }
    }

    // ── 手动路：真 harness（含 isCompacting 的转发）───────────────────────

    private static AgentHarness harness(CompactionObserver observer) {
        StreamFn neverCalled = (model, context, options) -> {
            throw new AssertionError(
                "streamFn 不该被调用：摘要生成器是 truncating 兜底，不走 LLM");
        };
        return AgentHarness.create(HarnessConfig.builder()
            .streamFn(neverCalled)
            .model(MODEL)
            .maxInputTokens(200_000)
            .contextWindow(ignored -> 200_000)
            .compactionSettings(SETTINGS)
            .summaryGenerator(SummaryGenerator.truncating())
            .compactionObserver(observer)
            .build());
    }

    private static Entry messageEntry(String id, boolean user, String text) {
        Message message = user
            ? new Message.UserMessage(List.of(new ContentBlock.TextContent(text)))
            : new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
        return new Entry.Message(id, 0, null, Instant.ofEpochMilli(1), message, false);
    }

    /** 四条消息的车道 —— 可压缩（pi 的 prepareCompaction 只拒空路径与末条 compaction 标记）。 */
    private static List<Entry> populatedEntries() {
        return List.of(
            messageEntry("e1", true, "first"),
            messageEntry("e2", false, "second"),
            messageEntry("e3", true, "third"),
            messageEntry("e4", false, "fourth"));
    }

    @Test
    void manualCompactionHoldsTheWindowForItsWholeBody() {
        var observer = new Sampling();
        try (var harness = harness(observer)) {
            observer.probe = () -> harness.isCompacting(AgentHarness.DEFAULT_LANE);
            harness.seedTranscript(AgentHarness.DEFAULT_LANE, populatedEntries());

            assertThat(harness.isCompacting(AgentHarness.DEFAULT_LANE)).isFalse();

            harness.compact(SETTINGS);

            // pi :1969 建控制器 → :1970 发 start ⇒ 观察者在 onStart 里已经看得见窗口。
            assertThat(observer.onStart).containsExactly(true);
            assertThat(observer.onEnd).containsExactly(true);
            assertThat(harness.isCompacting(AgentHarness.DEFAULT_LANE)).isFalse();
            // 夹具不空转：压缩真的落了一条标记 entry。
            assertThat(harness.snapshot(AgentHarness.DEFAULT_LANE).transcript().get(0))
                .isInstanceOf(Entry.Compaction.class);
        }
    }

    @Test
    void manualCompactionGuardStillClosesTheWindow() {
        // 空转录 ⇒ 守卫抛 NothingToCompactException。它抛在 pi 的
        // `if (!preparation) { throw … }` —— 那行在**建控制器之后**
        // （:1969 → :1970 → 守卫）⇒ 这一抛是在窗口内发生的；
        // 没有 finally 就会把标志永久留在「有压缩在飞」。
        var observer = new Sampling();
        try (var harness = harness(observer)) {
            observer.probe = () -> harness.isCompacting(AgentHarness.DEFAULT_LANE);

            assertThatThrownBy(() -> harness.compact(SETTINGS))
                .isInstanceOf(NothingToCompactException.class);

            assertThat(harness.isCompacting(AgentHarness.DEFAULT_LANE)).isFalse();
            assertThat(observer.onStart).containsExactly(true);
            assertThat(observer.onEnd).containsExactly(true);
        }
    }

    // ── 自动路：直接搭 CompactionExecutor（同 CompactionThresholdGateTest）──

    /** 只填压缩实际会读的槽位。 */
    private static ExecutionContext ctx(LaneState lane, CompactionSettings settings,
                                        CompactionObserver observer) {
        var tokenCounter = new ExecutionContext.TokenCounter();
        return new ExecutionContext(
            null, () -> MODEL, null, null, null,
            200_000, ignored -> 200_000, null,
            null, null, null,
            new HookSystem(lane), lane, () -> settings, null,
            tokenCounter, snapshotService(lane, tokenCounter), null, null, null,
            SummaryGenerator.truncating(), null, NoopTelemetryContext.INSTANCE, observer,
            null, null, null);
    }

    /** 真快照服务 —— 手动路会调 {@code publishState}，留着空白槽会 NPE。 */
    private static SnapshotService snapshotService(
            LaneState lane, ExecutionContext.TokenCounter tokenCounter) {
        return new SnapshotService(lane, new HarnessEventBus(), tokenCounter,
            () -> MODEL.modelName(), java.util.Set::of);
    }

    private static LaneState populatedLane() {
        var lane = new LaneState();
        lane.transcript.addAll(populatedEntries());
        HarnessUtils.rebuildLaneMessages(lane);
        return lane;
    }

    @Test
    void autoCompactionOpensTheWindowAfterStartAndClosesIt() {
        var lane = populatedLane();
        var observer = new Sampling();
        observer.probe = lane::isCompacting;
        var executor = new CompactionExecutor(ctx(lane, SETTINGS, observer));

        var outcome = executor.runAutoCompaction("default", lane, "threshold", false);

        assertThat(outcome.compacted()).isTrue();
        // pi :2290 发 start → :2291 建控制器 ⇒ 观察者在 onStart 里**还**看不见窗口
        // （与手动路相反）。
        assertThat(observer.onStart).containsExactly(false);
        assertThat(observer.onEnd).containsExactly(true);
        assertThat(lane.isCompacting()).isFalse();
    }

    @Test
    void skippedAutoCompactionNeverOpensTheWindow() {
        // 空转录 ⇒ pi 的 :2286 守卫（prepareCompaction 空返回）在**发 start 与
        // 建控制器之前** return（:2290-2291）⇒ 既不进窗口，也不发事件。
        var lane = new LaneState();
        var observer = new Sampling();
        observer.probe = lane::isCompacting;
        var executor = new CompactionExecutor(ctx(lane, SETTINGS, observer));

        var outcome = executor.runAutoCompaction("default", lane, "threshold", false);

        assertThat(outcome.compacted()).isFalse();
        assertThat(outcome.shouldContinue()).isFalse();
        assertThat(observer.onStart).isEmpty();
        assertThat(observer.onEnd).isEmpty();
        assertThat(lane.isCompacting()).isFalse();
    }

    @Test
    void skippedAutoCompactionForMissingSettingsNeverOpensTheWindow() {
        // pi :2277 的无模型守卫（这里是设置缺席这条同形的早返回）同样在窗口**外**。
        var lane = populatedLane();
        var observer = new Sampling();
        observer.probe = lane::isCompacting;
        var executor = new CompactionExecutor(ctx(lane, null, observer));

        assertThat(executor.runAutoCompaction("default", lane, "threshold", false).compacted())
            .isFalse();

        assertThat(observer.onStart).isEmpty();
        assertThat(lane.isCompacting()).isFalse();
    }
}
