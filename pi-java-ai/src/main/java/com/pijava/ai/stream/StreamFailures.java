package com.pijava.ai.stream;

import com.pijava.ai.AbortedException;
import com.pijava.ai.message.StopReason;

/**
 * pi {@code api/lazy.ts:80-83} 的对位：setup 失败时，终止信号已触发就记 {@code aborted}，
 * 否则记 {@code error}（{@code signal?.aborted ? "aborted" : "error"}）。
 *
 * <p>Java 没有 lazy 车道（每请求 eager 构造），中止以异常形态出现：{@link AbortedException}、
 * {@link InterruptedException}，或线程中断位已置。三者任一即为中止；无中止信号的真实失败仍是
 * {@code error} —— 否则会把配置错误伪装成用户取消，污染转录与重试。</p>
 */
public final class StreamFailures {

    private StreamFailures() {
    }

    /**
     * 由失败原因判定终局停因。
     *
     * @param failure 冒泡到流出口的异常，可 null
     * @return {@link StopReason#ABORTED}（中止在场）或 {@link StopReason#ERROR}
     */
    public static StopReason reasonFor(Throwable failure) {
        if (failure instanceof AbortedException
            || failure instanceof InterruptedException
            || Thread.currentThread().isInterrupted()) {
            return StopReason.ABORTED;
        }
        return StopReason.ERROR;
    }
}
