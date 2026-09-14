package com.pijava.ai.utils;

/**
 * 指数退避的延迟函数 —— pi {@code retryDelayMs(policy, attempt)}
 * （{@code packages/ai/src/utils/retry.ts:111-115}）的逐条移植
 * （package 3d，{@code docs/31 §8.22}）。两环共用（post-run ① 与摘要重试），
 * 所以住在 ai 工具层、与 {@link RetryableError} 同包同文件位。
 *
 * <p><b>形状</b>：{@code base * 2^max(0, attempt-1)}，attempt 为 1 起的重试序号；
 * {@code Number.isSafeInteger} 护栏 ⇒ 超出 2^53-1 的乘积折到该上界（Java 侧用
 * double 乘幂，与 JS 的数值语义同形，不溢出不回绕）；最后
 * {@code min(delay, maxAgentDelayMs ?? 60_000)} 封顶 —— pi-java 的
 * {@code Long cap} 参数上 {@code null ≙ ?? 默认}。</p>
 */
public final class RetryBackoff {

    /** pi {@code DEFAULT_MAX_AGENT_RETRY_DELAY_MS}（retry.ts:109）。 */
    public static final long DEFAULT_MAX_AGENT_RETRY_DELAY_MS = 60_000L;

    /** JS {@code Number.MAX_SAFE_INTEGER}（2^53-1）；超界的乘积折到这里。 */
    private static final double MAX_SAFE_INTEGER = 9_007_199_254_740_991.0;

    private RetryBackoff() {}

    /**
     * pi {@code retryDelayMs}：第 {@code attempt} 次（1 起）重试前的退避毫秒数。
     *
     * @param baseDelayMs    基准延迟（pi {@code policy.baseDelayMs}）
     * @param maxAgentDelayMs 封顶；{@code null} ≙ pi 的 {@code ?? 60_000}
     * @param attempt        重试序号，1 起（首次调用不是重试，不走这里）
     */
    public static long delayMs(long baseDelayMs, Long maxAgentDelayMs, int attempt) {
        double delay = baseDelayMs * Math.pow(2, Math.max(0, attempt - 1));
        double safeDelay = delay <= MAX_SAFE_INTEGER ? delay : MAX_SAFE_INTEGER;
        long cap = maxAgentDelayMs == null ? DEFAULT_MAX_AGENT_RETRY_DELAY_MS : maxAgentDelayMs;
        return (long) Math.min(safeDelay, (double) cap);
    }
}
