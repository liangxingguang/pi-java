package com.pijava.agent.harness;

/**
 * 自动重试的运行时设置 —— pi {@code settingsManager.getRetrySettings()}
 * （{@code settings-manager.ts:927-933}）返回形状的 harness 方言
 * （package 3d，{@code docs/31 §8.22}）。两环共用：post-run ① 的预算/退避与
 * 摘要重试（{@code completeSummarization} 的 {@code retryAssistantCall}）读
 * <b>同一份</b>设置（pi 注释原文：Uses the same {@code settings.retry} budget）。
 *
 * <p>默认值即 pi 的 {@code ??} 链：{@code enabled=true / maxRetries=3 /
 * baseDelayMs=2000 / maxAgentDelayMs=60_000}。{@code maxAgentDelayMs} 可空
 * （≙ pi 传进 {@code retryDelayMs} 的可选字段，{@code ?? 60_000} 由
 * {@code RetryBackoff} 兜底）。</p>
 *
 * @param enabled         自动重试总开关（pi {@code retry.enabled}）
 * @param maxRetries      每次「错误链」的最大重试数；初发不计
 * @param baseDelayMs     退避基准毫秒（{@code base * 2^(attempt-1)}）
 * @param maxAgentDelayMs 单次退避封顶；{@code null} ⇒ 60 秒
 */
public record RetrySettings(boolean enabled, int maxRetries, long baseDelayMs, Long maxAgentDelayMs) {

    /** pi 默认（settings-manager.ts:927-933 的 ?? 链）。 */
    public static RetrySettings defaults() {
        return new RetrySettings(true, 3, 2_000, 60_000L);
    }
}
