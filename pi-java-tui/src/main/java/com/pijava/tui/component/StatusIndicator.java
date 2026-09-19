package com.pijava.tui.component;

/**
 * 状态指示器槽位的取值 —— 对齐 pi
 * {@code modes/interactive/components/status-indicator.ts}。
 *
 * <p>pi 只有<b>一个</b>激活指示器槽（{@code activeStatusIndicator}），
 * {@code clearStatusIndicator(kind)} 的第一句是守卫：
 * {@code if (kind && activeStatusIndicator?.kind !== kind) return;} ——
 * 清 {@code retry} 不会误清并发中的 {@code compaction}。
 * 这是 {@code ChatScreen.clearIndicator(Kind)} 照抄的语义（docs/31 §8.38.1-(2)）。</p>
 *
 * <p>倒计时<b>不靠定时器</b>：pi 用 {@code CountdownTimer} 每秒 {@code requestRender}，
 * 而 pi-java 的 fullscreen 每 33 ms 整帧重绘
 * （{@code TamboUIAdapter.createRunner().tickRate(33ms)}）⇒ 按帧从截止时刻重算即等价；
 * inline（按需重绘）另配 1 Hz 唤醒（{@code util.CountdownWake}）。见 docs/31 §8.38.4。</p>
 */
public sealed interface StatusIndicator {

    /** pi {@code StatusIndicatorKind} 中本包可产生的三种。 */
    enum Kind { RETRY, COMPACTION, BRANCH_SUMMARY }

    /** 指示器种类（清位守卫按它比对）。 */
    Kind kind();

    /** 当前应显示的文本；含倒计时的变体按 {@code nowNanos} 重算。 */
    String textAt(long nowNanos);

    /** 是否含倒计时（inline 模式据此决定要不要每秒唤醒重绘）。 */
    default boolean ticking() {
        return false;
    }

    /**
     * 建 retry 指示器（pi {@code RetryStatusIndicator}，:51-81）；截止 = 现在 + {@code delayMs}。
     *
     * @param attempt       当前第几次（pi {@code event.attempt}）
     * @param maxAttempts   预算上限（pi {@code event.maxAttempts}）
     * @param delayMs       退避时长（pi 取它的 {@code ceil(/1000)} 作首帧）
     * @param interruptHint 取消提示键（pi {@code keyText("app.interrupt")} ⇒ {@code esc}）
     * @return 新的 retry 指示器
     */
    static Retry retry(int attempt, int maxAttempts, long delayMs, String interruptHint) {
        return new Retry(attempt, maxAttempts,
            System.nanoTime() + delayMs * 1_000_000L, interruptHint);
    }

    /**
     * retry 指示器 —— 文本逐字照 pi：
     * {@code Retrying (1/3) in 4s... (esc to cancel)}（秒数 = {@code ceil(剩余/1000)}）。
     */
    record Retry(int attempt, int maxAttempts, long deadlineNanos, String interruptHint)
            implements StatusIndicator {

        @Override
        public Kind kind() {
            return Kind.RETRY;
        }

        @Override
        public String textAt(long nowNanos) {
            long remainingMs = Math.max(0L, deadlineNanos - nowNanos) / 1_000_000L;
            return "Retrying (" + attempt + "/" + maxAttempts + ") in "
                + ((remainingMs + 999) / 1000) + "s... (" + interruptHint + " to cancel)";
        }

        @Override
        public boolean ticking() {
            return true;
        }
    }

    /**
     * compaction 指示器 —— 文本照 pi {@code CompactionStatusIndicator}（:85-100）。
     *
     * @param reasonLiteral {@code "manual"} / {@code "threshold"} / {@code "overflow"}
     *                      （{@code CompactionObserver} 的 pi 字面量，非新造枚举）；
     *                      缺席时落 pi 的 else 支（{@code Auto-compacting...}）
     * @param interruptHint 取消提示键（pi {@code keyText("app.interrupt")}）
     */
    record Compaction(String reasonLiteral, String interruptHint) implements StatusIndicator {

        @Override
        public Kind kind() {
            return Kind.COMPACTION;
        }

        @Override
        public String textAt(long nowNanos) {
            return label() + " (" + interruptHint + " to cancel)";
        }

        private String label() {
            if ("manual".equals(reasonLiteral)) {
                return "Compacting context...";
            }
            if ("overflow".equals(reasonLiteral)) {
                return "Context overflow detected, Auto-compacting...";
            }
            return "Auto-compacting...";
        }
    }

    /**
     * branchSummary 指示器 —— 文本照 pi {@code BranchSummaryStatusIndicator}（:102-112）。
     *
     * <p>⚠️ 今天<b>不可达</b>：branch summary 在 pi-java 无实现（台账 B1），
     * {@code summarization_retry_attempt_start} 的 source 恒为 {@code "compaction"}。
     * 照写是为了 B1 落地后自动生效 —— <b>不得</b>为它造一个假的可达性
     * （docs/31 §8.38.7-D）。</p>
     */
    record BranchSummary(String interruptHint) implements StatusIndicator {

        @Override
        public Kind kind() {
            return Kind.BRANCH_SUMMARY;
        }

        @Override
        public String textAt(long nowNanos) {
            return "Summarizing branch... (" + interruptHint + " to cancel)";
        }
    }
}
