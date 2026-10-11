package com.pijava.ai.http;

import java.time.Duration;

/**
 * 等响应头超时（pi {@code 6b07b4e57}：Mistral 的 {@code AbortSignal.timeout(timeoutMs)}
 * 只盖到响应头到达）。
 *
 * <p>只可能在<b>头阶段</b>抛出：响应头一旦到达，固定截止时间即释放，长思考流不会被切断
 * （body 停顿交给 HTTP client 的 idle 超时；JDK HttpClient 无该机制，残留见 docs/52 §5）。
 * 是非检查异常（要保类型穿过 postSse 边界，让车道映射 pi 文案）；
 * {@link RetryPolicy#shouldRetry(Exception)} 按通用 Exception 面认它，与 IO 错误同重试。</p>
 */
public final class HeaderTimeoutException extends RuntimeException {

    private final transient Duration timeout;

    /** @param timeout 触发的头超时值（文案只取毫秒数）。 */
    public HeaderTimeoutException(Duration timeout) {
        super("Response headers timed out after " + timeout.toMillis() + "ms");
        this.timeout = timeout;
    }

    /** 触发的头超时值。 */
    public Duration timeout() {
        return timeout;
    }
}
