package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import com.pijava.agent.compaction.CompactionObserver;
import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.agent.context.ContextUsageEstimator;
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
 * package 3c 的 post-run 压缩判定哨兵（pi {@code _handlePostAgentRun} ②③ +
 * {@code _checkCompaction}，{@code agent-session.ts:1116-1144, 2154-2258}；
 * {@code docs/31 §8.21}）。一条守卫一颗钉：G0 设置、G1 aborted、G3 sameModel、
 * G4 陈旧边界、C1/C2 溢出与可恢复截断、R0/R1/R2 闩与删尾、T1 的锚点校验、
 * T2 阈值，以及 ③ 的「队列有货也续跑」。事件形状（R1 只发 end、end 的
 * willRetry/errorMessage 文案）一并钉在 recorder 上。
 */
class PostRunCompactionCheckTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "gate-model");
    private static final CompactionSettings SETTINGS = new CompactionSettings(true, 10, 20_000);
    private static final String OVERFLOW_TEXT =
        "Context overflow recovery failed after one compact-and-retry attempt. "
            + "Try reducing context or switching to a larger-context model.";
    private static final String TRUNCATED_TEXT =
        "Truncated response recovery failed after one compact-and-retry attempt.";

    /** 记录 observer 事件的假接收器（agent-core 侧只见字符串，编码 pi 事件字段）。 */
    private static final class Recorder implements CompactionObserver {
        final List<String> starts = new ArrayList<>();
        final List<String> ends = new ArrayList<>();
        CompactionResult lastResult;

        @Override
        public void onStart(String reason) {
            starts.add(reason);
        }

        @Override
        public void onEnd(String reason, CompactionResult result, boolean aborted,
                          boolean willRetry, String errorMessage) {
            ends.add(reason + "|" + (result == null ? "-" : "result")
                + "|" + aborted + "|" + willRetry + "|" + errorMessage);
            lastResult = result;
        }
    }

    /** 只填 3c 判据与压缩体会读的槽位，其余 null（构造器兜默认）。 */
    private static ExecutionContext ctx(LaneState lane, CompactionSettings settings,
                                        ModelId<?> model, ToIntFunction<ModelId<?>> window,
                                        ToIntFunction<ModelId<?>> maxOut, CompactionObserver obs) {
        return new ExecutionContext(
            null, () -> model, null, null, null,
            200_000, window, maxOut,
            null, null, null,
            new HookSystem(lane), lane, () -> settings, null,
            new ExecutionContext.TokenCounter(), null, null, null, null,
            SummaryGenerator.truncating(), null, NoopTelemetryContext.INSTANCE, obs,
            null, null, null);
    }

    private static final ToIntFunction<ModelId<?>> WINDOW_200 = id -> 200;
    private static final ToIntFunction<ModelId<?>> MAX_OUT_8192 = id -> 8_192;

    private static Entry messageEntry(String id, Message message) {
        return new Entry.Message(id, 0, null, Instant.ofEpochMilli(1), message, false);
    }

    private static Entry.Compaction marker(String id, long timestampMs) {
        return new Entry.Compaction(id, 0, null, Instant.ofEpochMilli(timestampMs),
            "old summary", "e1", List.of(), 10, null, null);
    }

    private static Usage usage(double input, double output, double cacheRead) {
        return new Usage(input, output, cacheRead, 0, null, null,
            input + output + cacheRead, Usage.Cost.zero());
    }

    /** 终局助手消息（9 组件的全投影形状，3a）。 */
    private static Message.AssistantMessage assistant(String stopReason, String provider,
            String model, Usage usage, Long timestampMs, String errorMessage) {
        return new Message.AssistantMessage(List.of(), stopReason, null, null,
            provider, model, usage,
            timestampMs == null ? null : Instant.ofEpochMilli(timestampMs), errorMessage, null);
    }

    private static Message.AssistantMessage overflowError() {
        return assistant("error", "faux", "gate-model", null, 1L, "prompt is too long: 210000 tokens");
    }

    /** [user, 溢出 error 助手] 的车道：C1 命中、可压。 */
    private static LaneState overflowLane() {
        var lane = new LaneState();
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")));
        lane.transcript.add(messageEntry("e1", user));
        lane.messages.add(user);
        lane.transcript.add(messageEntry("e2", overflowError()));
        lane.messages.add(overflowError());
        return lane;
    }

    private record Rig(LaneState lane, PostRunCompactionCheck check, Recorder obs) {}

    private static Rig rig(LaneState lane, CompactionSettings settings, ModelId<?> model) {
        var obs = new Recorder();
        var ctx = ctx(lane, settings, model, WINDOW_200, MAX_OUT_8192, obs);
        var executor = new CompactionExecutor(ctx);
        return new Rig(lane, new PostRunCompactionCheck(ctx, executor), obs);
    }

    private static boolean compacted(LaneState lane) {
        return lane.transcript.stream().anyMatch(Entry.Compaction.class::isInstance);
    }

    // ═══════════════════════════════════════════════════════════
    // G0/G1 —— 设置与 aborted
    // ═══════════════════════════════════════════════════════════

    @Test
    void disabledSettingsShortCircuit() {
        var r = rig(overflowLane(), null, MODEL);
        assertThat(r.check().checkAfterRun("default", r.lane(), overflowError())).isFalse();
        assertThat(r.obs().starts).isEmpty();
    }

    @Test
    void abortedSkippedAfterRunButPrePromptCatchesIt() {
        // G1：post-run 跳过用户取消的响应（pi :2158-2159）；prompt 起手前不跳
        // （skipAbortedCheck=false，:1258-1263「catches aborted responses」）——
        // 那一轮把上下文喂大了，阈值路 T2 照跑。
        var lane = new LaneState();
        var big = new Message.UserMessage(List.of(
            new ContentBlock.TextContent("x".repeat(4_000))));
        var aborted = assistant("aborted", "faux", "gate-model", null, 1L, null);
        lane.transcript.add(messageEntry("e1", big));
        lane.transcript.add(messageEntry("e2", aborted));
        lane.messages.add(big);
        lane.messages.add(aborted);
        var r = rig(lane, SETTINGS, MODEL);

        assertThat(r.check().checkAfterRun("default", lane, aborted)).isFalse();
        assertThat(r.obs().starts).isEmpty();

        r.check().checkBeforePrompt("default", lane);
        assertThat(r.obs().starts).containsExactly("threshold");
        assertThat(r.obs().ends).hasSize(1);
        assertThat(compacted(lane)).isTrue();
    }

    // ═══════════════════════════════════════════════════════════
    // G3/G4 —— sameModel 与陈旧边界
    // ═══════════════════════════════════════════════════════════

    @Test
    void differentModelOverflowTextIgnored() {
        // pi 换模型后的旧溢出错误不该压新模型（:2167-2168）；此处窗口巨大，
        // 阈值线也不过 ⇒ 整条链静默。
        var other = assistant("error", "other", "gate-model", null, 1L, "prompt is too long");
        var lane = new LaneState();
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")));
        lane.transcript.add(messageEntry("e1", user));
        lane.messages.add(user);
        lane.transcript.add(messageEntry("e2", other));
        lane.messages.add(other);
        var obs = new Recorder();
        var ctx = ctx(lane, SETTINGS, MODEL, id -> 1_000_000, MAX_OUT_8192, obs);
        var check = new PostRunCompactionCheck(ctx, new CompactionExecutor(ctx));
        assertThat(check.checkAfterRun("default", lane, other)).isFalse();
        assertThat(lane.overflowRecoveryAttempted).isFalse();
        assertThat(compacted(lane)).isFalse();
        assertThat(obs.starts).isEmpty();
    }

    @Test
    void messageAtOrBeforeCompactionBoundarySkips() {
        // G4：早于（或等于）最新压缩边界的消息不再触发（:2172-2178）。
        var lane = new LaneState();
        lane.transcript.add(messageEntry("e1",
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")))));
        lane.transcript.add(marker("c1", 5_000));
        lane.transcript.add(messageEntry("e2", assistant("error", "faux", "gate-model",
            null, 5_000L, "prompt is too long")));
        var r = rig(lane, SETTINGS, MODEL);
        var stale = assistant("error", "faux", "gate-model", null, 5_000L, "prompt is too long");
        assertThat(r.check().checkAfterRun("default", lane, stale)).isFalse();
        assertThat(r.obs().starts).isEmpty();
        assertThat(lane.transcript.stream().filter(Entry.Compaction.class::isInstance).count())
            .isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════
    // C1/C2 + R0/R1/R2 —— 溢出恢复的一次性预算
    // ═══════════════════════════════════════════════════════════

    @Test
    void overflowErrorLatchesDropsCopyTailAndRetries() {
        var lane = overflowLane();
        var r = rig(lane, SETTINGS, MODEL);
        boolean again = r.check().checkAfterRun("default", lane, overflowError());

        assertThat(again).isTrue();                       // willRetry ⇒ 驱动 continue
        assertThat(lane.overflowRecoveryAttempted).isTrue(); // R2 置闩
        assertThat(lane.messages)
            .noneMatch(m -> m instanceof Message.AssistantMessage); // 副本摘掉 error 助手（:2214-2218）
        assertThat(compacted(lane)).isTrue();             // 日志已压
        assertThat(r.obs().starts).containsExactly("overflow");
        assertThat(r.obs().ends).containsExactly("overflow|result|false|true|null");
    }

    @Test
    void latchedOverflowAnnouncesFailureWithEndOnly() {
        // R1：预算只有一次。pi :2194-2211 只发 end{overflow}（**没有 start**），
        // 文案固定，不再压。
        var lane = overflowLane();
        lane.overflowRecoveryAttempted = true;
        var r = rig(lane, SETTINGS, MODEL);
        assertThat(r.check().checkAfterRun("default", lane, overflowError())).isFalse();
        assertThat(r.obs().starts).isEmpty();
        assertThat(r.obs().ends).containsExactly("overflow|-|false|false|" + OVERFLOW_TEXT);
        assertThat(compacted(lane)).isFalse();
    }

    @Test
    void stopOverflowCompactsOnceWithoutRetryOrLatch() {
        // R0（:2186-2191）：case 2 的 stop 收尾「压而不重试」—— continue 接不上
        // 已完成的响应；闩不置（预算没花）。
        var stop = assistant("stop", "faux", "gate-model", usage(210_000, 5, 0), 1L, null);
        var lane = new LaneState();
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")));
        lane.transcript.add(messageEntry("e1", user));
        lane.messages.add(user);
        lane.transcript.add(messageEntry("e2", stop));
        lane.messages.add(stop);
        var r = rig(lane, SETTINGS, MODEL);
        assertThat(r.check().checkAfterRun("default", lane, stop)).isFalse(); // 无队列
        assertThat(lane.overflowRecoveryAttempted).isFalse();
        assertThat(compacted(lane)).isTrue();
        assertThat(lane.messages).anyMatch(m -> m instanceof Message.AssistantMessage); // 副本未摘
        assertThat(r.obs().starts).containsExactly("overflow");
        assertThat(r.obs().ends).containsExactly("overflow|result|false|false|null");
    }

    @Test
    void recoverableLengthLatchesAndRetries() {
        // C2（:2184）：length + output 低于钳制前上限 ⇒ 一次有界恢复。
        var truncated = assistant("length", "faux", "gate-model", usage(10_000, 500, 0), 1L, null);
        var lane = new LaneState();
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")));
        lane.transcript.add(messageEntry("e1", user));
        lane.messages.add(user);
        lane.transcript.add(messageEntry("e2", truncated));
        lane.messages.add(truncated);
        var r = rig(lane, SETTINGS, MODEL);
        assertThat(r.check().checkAfterRun("default", lane, truncated)).isTrue();
        assertThat(lane.overflowRecoveryAttempted).isTrue();
        assertThat(compacted(lane)).isTrue();
    }

    @Test
    void latchedTruncationAnnouncesItsOwnText() {
        var truncated = assistant("length", "faux", "gate-model", usage(10_000, 500, 0), 1L, null);
        var lane = new LaneState();
        lane.transcript.add(messageEntry("e1",
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")))));
        lane.transcript.add(messageEntry("e2", truncated));
        lane.overflowRecoveryAttempted = true;
        var r = rig(lane, SETTINGS, MODEL);
        assertThat(r.check().checkAfterRun("default", lane, truncated)).isFalse();
        assertThat(r.obs().ends).containsExactly("overflow|-|false|false|" + TRUNCATED_TEXT);
    }

    // ═══════════════════════════════════════════════════════════
    // T1/T2 —— 阈值读数、锚点校验与载荷
    // ═══════════════════════════════════════════════════════════

    /** [marker(5000), 带用量的好助手(锚点时间戳), error「boom」(6000)] 的车道。 */
    private static LaneState anchoredLane(long usageMsgTimestampMs) {
        var lane = new LaneState();
        var anchored = assistant("stop", "faux", "gate-model", usage(500, 0, 0),
            usageMsgTimestampMs, null);
        var err = assistant("error", "faux", "gate-model", null, 6_000L, "boom");
        lane.transcript.add(marker("c1", 5_000));
        lane.transcript.add(messageEntry("e2", anchored));
        lane.transcript.add(messageEntry("e3", err));
        lane.messages.add(anchored);
        lane.messages.add(err);
        return lane;
    }

    @Test
    void staleUsageAnchorSkipsThreshold() {
        // T1（:2236-2247）：error 退回估算；估算挂着**压缩前**的用量锚点 ⇒ 整个
        // 判定返回 false（防刚压完就被旧用量再触发）。
        var lane = anchoredLane(4_000);
        var r = rig(lane, SETTINGS, MODEL);
        var err = assistant("error", "faux", "gate-model", null, 6_000L, "boom");
        assertThat(r.check().checkAfterRun("default", lane, err)).isFalse();
        assertThat(r.obs().starts).isEmpty();
        assertThat(lane.transcript.stream().filter(Entry.Compaction.class::isInstance).count())
            .isEqualTo(1);
    }

    @Test
    void freshUsageAnchorFiresThresholdWithPureTokensAfter() {
        // 同一形状，锚点新鲜 ⇒ T2 发火（500 > 200-10）。end 的 result 里
        // tokensBefore 走用量、estimatedTokensAfter 走**重建后的纯字符和**（:2383）。
        var lane = anchoredLane(6_000);
        var r = rig(lane, SETTINGS, MODEL);
        var err = assistant("error", "faux", "gate-model", null, 6_000L, "boom");
        assertThat(r.check().checkAfterRun("default", lane, err)).isFalse(); // threshold 不重试
        assertThat(r.obs().starts).containsExactly("threshold");
        assertThat(r.obs().ends).containsExactly("threshold|result|false|false|null");
        var result = r.obs().lastResult;
        assertThat(result).isNotNull();
        assertThat(result.tokensBefore()).isEqualTo(500);
        assertThat(result.estimatedTokensAfter())
            .isEqualTo((long) ContextUsageEstimator
                .estimateMessagesTokens(List.copyOf(lane.messages)));
        assertThat(result.estimatedTokensAfter()).isLessThan(result.tokensBefore());
    }

    // ═══════════════════════════════════════════════════════════
    // ③ —— 队列兜底
    // ═══════════════════════════════════════════════════════════

    @Test
    void queuedMessagesAloneDriveAContinuation() {
        // pi :1143：压缩判定没发火，但队列有货 ⇒ post-run 仍要 continue。
        var lane = new LaneState();
        var ok = assistant("stop", "faux", "gate-model", usage(5, 0, 0), 1L, null);
        lane.transcript.add(messageEntry("e1",
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")))));
        lane.transcript.add(messageEntry("e2", ok));
        lane.messages.add(ok);
        lane.steerQueue.add(new LaneInfo.QueuedItem("next", 0));
        var r = rig(lane, SETTINGS, MODEL);
        assertThat(r.check().checkAfterRun("default", lane, ok)).isTrue();
        assertThat(r.obs().starts).isEmpty();   // 没压，只是队列
        assertThat(compacted(lane)).isFalse();
    }

    @Test
    void nullLastAssistantShortCircuitsBeforeQueues() {
        // pi :1118-1121：本 pass 没见过助手消息 ⇒ 直接 false，队列也不算。
        var lane = new LaneState();
        lane.steerQueue.add(new LaneInfo.QueuedItem("next", 0));
        var r = rig(lane, SETTINGS, MODEL);
        assertThat(r.check().checkAfterRun("default", lane, null)).isFalse();
    }
}
