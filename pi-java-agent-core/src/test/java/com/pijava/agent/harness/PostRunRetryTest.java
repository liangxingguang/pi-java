package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.pijava.agent.compaction.CompactionObserver;
import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.agent.hook.HookSystem;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.telemetry.NoopTelemetryContext;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环 A（post-run ①）的守卫哨兵 —— pi {@code _isRetryableError}/{@code _prepareRetry}/
 * {@code _willRetryAfterAgentEnd}（{@code agent-session.ts:2876-2880, 2917-2965,
 * 721-733}）与 {@code _handlePostAgentRun} 全序（{@code :1116-1144}）的移植钉
 * （package 3d，{@code docs/31 §8.22}）。
 *
 * <p>退避延迟一律 base 1ms ⇒ 测试不等真延迟；中止路用恒 true 的谓词即时掐。</p>
 */
class PostRunRetryTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "gate-model");
    private static final int WINDOW = 200;
    private static final CompactionSettings NO_COMPACTION = null;

    private static final class RetryLines implements RetryObserver {
        final List<String> lines = new ArrayList<>();

        @Override
        public void onAutoRetryStart(int attempt, int maxAttempts, long delayMs,
                                     String errorMessage) {
            lines.add("start|" + attempt + "|" + maxAttempts + "|" + errorMessage);
        }

        @Override
        public void onAutoRetryEnd(boolean success, int attempt, String finalError) {
            lines.add("end|" + success + "|" + attempt + "|" + finalError);
        }
    }

    private static final class CompactionLines implements CompactionObserver {
        final List<String> starts = new ArrayList<>();
        final List<String> ends = new ArrayList<>();

        @Override
        public void onStart(String reason) {
            starts.add(reason);
        }

        @Override
        public void onEnd(String reason, CompactionResult result,
                          boolean aborted, boolean willRetry, String errorMessage) {
            ends.add(reason + "|" + (errorMessage == null ? "null" : errorMessage));
        }
    }

    private static ExecutionContext ctx(LaneState lane, RetrySettings retrySettings,
                                        BooleanSupplier aborted, RetryObserver observer) {
        return ctx(lane, retrySettings, aborted, observer, NO_COMPACTION, CompactionObserver.NOOP);
    }

    private static ExecutionContext ctx(LaneState lane, RetrySettings retrySettings,
                                        BooleanSupplier aborted, RetryObserver observer,
                                        CompactionSettings compactionSettings,
                                        CompactionObserver compactionObserver) {
        return new ExecutionContext(
            null, () -> MODEL, null, null, null,
            200_000, id -> WINDOW, null,
            null, null, null,
            new HookSystem(lane), lane, () -> compactionSettings, null,
            new ExecutionContext.TokenCounter(), null, null, null, null,
            SummaryGenerator.truncating(), null, NoopTelemetryContext.INSTANCE,
            compactionObserver, () -> retrySettings, aborted, observer);
    }

    private static final RetrySettings FAST = new RetrySettings(true, 3, 1, 1_000L);

    private static Message.AssistantMessage error(String errorMessage) {
        return new Message.AssistantMessage(List.<ContentBlock>of(), "error", null,
            null, null, null, null, null, errorMessage, null);
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    /** [user, 尾助手] 的车道（副本与日志同尾 —— 摘尾判据看副本尾）。 */
    private static LaneState laneWith(Message.AssistantMessage tail) {
        var lane = new LaneState();
        lane.messages.add(user("hello"));
        lane.messages.add(tail);
        return lane;
    }

    // ═══════════════════════════════════════════════════════════
    // isRetryableError —— 溢出交接 + 白名单
    // ═══════════════════════════════════════════════════════════

    @Test
    void overflowIsHandedToCompactionNotRetry() {
        // pi :2877：isContextOverflow ⇒ false（哪怕文本也撞白名单词）。
        var lane = new LaneState();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, RetryObserver.NOOP));
        assertThat(retry.isRetryableError(
            error("prompt is too long: 210000 tokens, model context window is 200"))).isFalse();
    }

    @Test
    void transientErrorIsRetryable() {
        var lane = new LaneState();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, RetryObserver.NOOP));
        assertThat(retry.isRetryableError(error("overloaded"))).isTrue();
        assertThat(retry.isRetryableError(error("invalid api key"))).isFalse();
        assertThat(retry.isRetryableError(error(null))).isFalse();
    }

    // ═══════════════════════════════════════════════════════════
    // prepareRetry —— 计数、事件、只摘副本、取消
    // ═══════════════════════════════════════════════════════════

    @Test
    void disabledShortCircuitsWithoutTouchingAnything() {
        var lane = laneWith(error("overloaded"));
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, new RetrySettings(false, 3, 1, 1_000L),
            () -> false, lines));
        assertThat(retry.prepareRetry(lane, error("overloaded"))).isFalse();
        assertThat(lane.retryAttempt).isZero();
        assertThat(lines.lines).isEmpty();
    }

    @Test
    void budgetExhaustionKeepsCompletedCount() {
        // pi :2926-2931：attempt++ 后 **>** maxRetries ⇒ 回退计数返回 false ——
        // 保留完成计数正是给终局失败事件读 attempt 用的。
        var lane = laneWith(error("overloaded"));
        lane.retryAttempt = 3;
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, lines));
        var msg = error("overloaded");
        assertThat(retry.prepareRetry(lane, msg)).isFalse();
        assertThat(lane.retryAttempt).isEqualTo(3);
        assertThat(lines.lines).isEmpty();
    }

    @Test
    void prepareEmitsStartDropsCopyAndReturnsTrue() {
        var msg = error("overloaded");
        var lane = laneWith(msg);
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, lines));
        assertThat(retry.prepareRetry(lane, msg)).isTrue();
        assertThat(lane.retryAttempt).isEqualTo(1);
        assertThat(lines.lines).containsExactly("start|1|3|overloaded");
        // 只摘副本尾的助手消息；pi :2937-2941 的「keep in session for history」——
        // transcript 在本单测里本就空，副本从 [user, assistant] 缩成 [user]。
        assertThat(lane.messages).hasSize(1);
        assertThat(lane.messages.get(0)).isInstanceOf(Message.UserMessage.class);
    }

    @Test
    void emptyErrorMessageBecomesUnknownError() {
        var msg = error("");
        var lane = laneWith(msg);
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, lines));
        assertThat(retry.prepareRetry(lane, msg)).isTrue();
        assertThat(lines.lines).containsExactly("start|1|3|Unknown error");
    }

    @Test
    void onlyAssistantTailIsDropped() {
        // pi 的 drop 判据是尾条 role === "assistant"；副本停在用户消息上时不动。
        var lane = new LaneState();
        lane.messages.add(user("hello"));
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, lines));
        assertThat(retry.prepareRetry(lane, error("overloaded"))).isTrue();
        assertThat(lane.messages).hasSize(1);
    }

    @Test
    void abortDuringBackoffEmitsCancelledEndAndZeros() {
        var msg = error("overloaded");
        var lane = laneWith(msg);
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> true, lines));
        assertThat(retry.prepareRetry(lane, msg)).isFalse();
        assertThat(lane.retryAttempt).isZero(); // pi :2951 的清零
        // 摘尾已发生（drop 在睡眠之前，pi :2937-2945 顺序）—— 取消不回滚副本。
        assertThat(lane.messages).hasSize(1);
        assertThat(lines.lines).containsExactly(
            "start|1|3|overloaded",
            "end|false|1|Retry cancelled");
    }

    // ═══════════════════════════════════════════════════════════
    // _willRetryAfterAgentEnd —— agent_end 装饰门
    // ═══════════════════════════════════════════════════════════

    @Test
    void decorationGatesMirrorPiOrder() {
        var lane = laneWith(error("overloaded"));
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, RetryObserver.NOOP));
        assertThat(retry.retryWouldFollow(lane, error("overloaded"))).isTrue();
        lane.retryAttempt = 3;
        // >= 门（注意 ① 的预算门是 >）：装饰先于本 pass 的 ① 读计数 ⇒ 预算已尽 false。
        assertThat(retry.retryWouldFollow(lane, error("overloaded"))).isFalse();
        lane.retryAttempt = 0;
        assertThat(retry.retryWouldFollow(lane, null)).isFalse();
        assertThat(retry.retryWouldFollow(lane, error("invalid api key"))).isFalse();
        var disabled = new PostRunRetry(ctx(lane, new RetrySettings(false, 3, 1, 1_000L),
            () -> false, RetryObserver.NOOP));
        assertThat(disabled.retryWouldFollow(lane, error("overloaded"))).isFalse();
    }

    // ═══════════════════════════════════════════════════════════
    // checkAfterRun 全序 —— ① 命中则本轮不跑 ②；终局失败块收尾
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryWinsBeforeCompactionThisPass() {
        // 序哨兵（§8.22.3-③）：瞬断 error + 阈值已过 ⇒ ① continue，②**不发**。
        // 「阈值已过」必须是真的：窗口 200 − reserve 10 ⇒ 线在 190，尾助手无 usage
        // ⇒ T1 折回 chars/4 估算 —— 旧版 1 条 "hello" 读数 ≈2，② 在**任何**顺序下
        // 都不发，哨兵恒真（3d 换序反证当场现形）。1000+ 字符 ⇒ 估算 ≈252 > 190。
        var msg = error("overloaded");
        var lane = new LaneState();
        var bigUser = user("x".repeat(1_000));
        lane.messages.add(bigUser);
        lane.messages.add(msg);
        // ② 还得过 runAutoCompaction 的 :638 静默跳（空 transcript ⇒ 直接 SKIPPED，
        // 不发事件）—— 序哨兵要有牙，日志必须非空且末条不是压缩。
        var now = java.time.Instant.now();
        lane.transcript.add(new com.pijava.agent.entry.Entry.Message(
            "e-user", 0, null, now, bigUser, null));
        lane.transcript.add(new com.pijava.agent.entry.Entry.Message(
            "e-asst", 1, "e-user", now, msg, null));
        var retries = new RetryLines();
        var compactions = new CompactionLines();
        var ctx = ctx(lane, FAST, () -> false, retries,
            new CompactionSettings(true, 10, 5), compactions);
        var check = new PostRunCompactionCheck(ctx, new CompactionExecutor(ctx));
        assertThat(check.checkAfterRun("default", lane, msg)).isTrue();
        assertThat(retries.lines).containsExactly("start|1|3|overloaded");
        assertThat(compactions.starts).isEmpty();
        assertThat(compactions.ends).isEmpty();
    }

    @Test
    void terminalFailureEmitsFinalErrorOnceBudgetDead() {
        // ① 没救活（预算耗尽 ⇒ prepareRetry false 且保留计数）+ error 收尾 ⇒
        // 终局失败块发 end{false, attempt, finalError} + 清零（pi :1127-1134）。
        var msg = error("overloaded");
        var lane = laneWith(msg);
        lane.retryAttempt = 3;
        var retries = new RetryLines();
        var ctx = ctx(lane, FAST, () -> false, retries);
        var check = new PostRunCompactionCheck(ctx, new CompactionExecutor(ctx));
        assertThat(check.checkAfterRun("default", lane, msg)).isFalse();
        assertThat(lane.retryAttempt).isZero();
        assertThat(retries.lines).containsExactly("end|false|3|overloaded");
    }

    @Test
    void terminalFailureFinalErrorPassesThroughEmptyAsPi() {
        // pi 的 finalError: msg.errorMessage —— 空串原样透传（undefined ≙ null 形）。
        var msg = error(null);
        var lane = laneWith(msg);
        lane.retryAttempt = 2;
        var retries = new RetryLines();
        var ctx = ctx(lane, FAST, () -> false, retries);
        var check = new PostRunCompactionCheck(ctx, new CompactionExecutor(ctx));
        // error(null) 不可重试 ⇒ ① false；重试链早已在别处清零过 ⇒ 这里 2>0 终局收尾。
        assertThat(check.checkAfterRun("default", lane, msg)).isFalse();
        assertThat(retries.lines).containsExactly("end|false|2|null");
    }
}
