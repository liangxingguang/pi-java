package com.pijava.ai.provider.faux;

/**
 * docs/20 §3.2／§6.5：pi {@code RegisterFauxProviderOptions.deferred}
 * （{@code providers/faux.ts:123-127}）。
 *
 * @param pendingFetches 在真正响应就绪前先返回原 handle 的取回次数；负数按 0
 * @param pollAfterMs {@code DeferredHandle.pollAfterMs} 的建议值；{@code null} ≙ 不写该键
 */
public record FauxDeferredOptions(int pendingFetches, Long pollAfterMs) {

    /** pi 的默认：0 次 pending 取回、不写 {@code pollAfterMs}。 */
    public static FauxDeferredOptions defaults() {
        return new FauxDeferredOptions(0, null);
    }

    /** {@link #pendingFetches} 的 pi 归一：{@code Math.max(0, Math.floor(...))}。 */
    public int normalizedPendingFetches() {
        return Math.max(0, pendingFetches);
    }
}
