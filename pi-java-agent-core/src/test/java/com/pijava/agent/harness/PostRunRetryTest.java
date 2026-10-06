package com.pijava.agent.harness;

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
 * 环 A（post-run ①）的守卫哨兵 —— pi {@code _isRetryableError}/{@code _prepareRetry}/
 * {@code _willRetryAfterAgentEnd}（{@code agent-session.ts:2876-2880, 2917-2965,
 * 721-733}）与 {@code _handlePostAgentRun} 全序（{@code :1116-1144}）的移植钉
 * （package 3d，{@code 原 docs/31 §8.22}）。
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
            compactionObserver, () -> retrySettings, aborted, observer, null, null, null);
    }

    private static final RetrySettings FAST = new RetrySettings(true, 3, 1, 1_000L);

    private static Message.AssistantMessage error(String errorMessage) {
        return new Message.AssistantMessage(List.<ContentBlock>of(), "error", null,
            null, null, null, null, null, errorMessage, null);
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    /** [user, 尾助手] 只进工作副本的车道（用于不走到持久省略的守卫路径）。 */
    private static LaneState laneWith(Message.AssistantMessage tail) {
        var lane = new LaneState();
        lane.messages.add(user("hello"));
        lane.messages.add(tail);
        return lane;
    }

    /**
     * [user, 尾助手] 同时进工作副本与 transcript 的车道（B172：重试省略按对象
     * 身份从 transcript 解析目标条目，沿 parentId 链）。
     */
    private static LaneState persistedLaneWith(Message.AssistantMessage tail) {
        var lane = laneWith(tail);
        var now = java.time.Instant.now();
        lane.transcript.add(new Entry.Message(
            "e-user", 0, null, now, user("hello"), null));
        lane.transcript.add(new Entry.Message(
            "e-asst", 1, "e-user", now, tail, null));
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
        var lane = persistedLaneWith(msg);
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, lines));
        assertThat(retry.prepareRetry(lane, msg)).isTrue();
        assertThat(lane.retryAttempt).isEqualTo(1);
        assertThat(lines.lines).containsExactly("start|1|3|overloaded");
        // B172：不再手工摘尾 —— 持久 omission edit 落盘后按 transcript 重建副本，
        // error 助手投影为空，副本从 [user, assistant] 变成 [user]。
        assertThat(lane.messages).hasSize(1);
        assertThat(lane.messages.get(0)).isInstanceOf(Message.UserMessage.class);
    }

    @Test
    void emptyErrorMessageBecomesUnknownError() {
        var msg = error("");
        var lane = persistedLaneWith(msg);
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, lines));
        assertThat(retry.prepareRetry(lane, msg)).isTrue();
        assertThat(lines.lines).containsExactly("start|1|3|Unknown error");
    }

    @Test
    void unknownTargetIsSilentlySkipped() {
        // 传入的失败助手既不在 transcript 也不在工作副本（pi :1219 if (!targetId)
        // continue）⇒ 不追加 edit；随后仍照 pi _refreshFinalizedContext 重建，
        // transcript 空 ⇒ 副本清空。
        var lane = new LaneState();
        lane.messages.add(user("hello"));
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, lines));
        assertThat(retry.prepareRetry(lane, error("overloaded"))).isTrue();
        assertThat(lane.transcript).isEmpty();
        assertThat(lane.messages).isEmpty();
    }

    @Test
    void abortDuringBackoffEmitsCancelledEndAndZeros() {
        var msg = error("overloaded");
        var lane = persistedLaneWith(msg);
        var lines = new RetryLines();
        var retry = new PostRunRetry(ctx(lane, FAST, () -> true, lines));
        assertThat(retry.prepareRetry(lane, msg)).isFalse();
        assertThat(lane.retryAttempt).isZero(); // pi :2951 的清零
        // 持久省略与重建在睡眠之前（pi :2937-2945 顺序）—— 取消不回滚。
        assertThat(lane.messages).hasSize(1);
        assertThat(lines.lines).containsExactly(
            "start|1|3|overloaded",
            "end|false|1|Retry cancelled");
    }

    // ═══════════════════════════════════════════════════════════
    // B172（docs/19）：重试前的持久 omission edit
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryOmissionIsPersistedAsAContextEdit() {
        // pi _prepareRetry → _omitRecoveryAttempt(message)：transcript 尾必须是
        // 目标为失败助手条目、replacement=null 的 context_edit。
        var msg = error("overloaded");
        var lane = persistedLaneWith(msg);
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, RetryObserver.NOOP));
        assertThat(retry.prepareRetry(lane, msg)).isTrue();

        assertThat(lane.transcript).hasSize(3);
        var edit = lane.transcript.get(2);
        assertThat(edit).isInstanceOf(Entry.ContextEdit.class);
        var contextEdit = (Entry.ContextEdit) edit;
        assertThat(contextEdit.targetId()).isEqualTo("e-asst");
        assertThat(contextEdit.replacement()).isNull();
    }

    @Test
    void projectedAssistantWithoutSourceEntryThrows() {
        // pi :1216-1218：失败助手在工作副本里、transcript 无源条目 ⇒ 固定错误，
        // 不允许静默摘尾了事。
        var msg = error("overloaded");
        var lane = laneWith(msg);
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, RetryObserver.NOOP));
        assertThatThrownBy(() -> retry.prepareRetry(lane, msg))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Cannot persist recovery omission because a projected message has no source entry");
    }

    @Test
    void assistantInNeitherPlaceAppendsNoEdit() {
        // 失败助手两处皆无 ⇒ 无 edit、prepareRetry 仍返回 true（与 unknownTarget
        // 同一跳过分支，这里钉「不产生 edit」）。
        var lane = new LaneState();
        lane.messages.add(user("hello"));
        var retry = new PostRunRetry(ctx(lane, FAST, () -> false, RetryObserver.NOOP));
        assertThat(retry.prepareRetry(lane, error("overloaded"))).isTrue();
        assertThat(lane.transcript).isEmpty();
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
