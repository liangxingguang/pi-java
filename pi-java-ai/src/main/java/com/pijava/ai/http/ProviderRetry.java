package com.pijava.ai.http;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Java 移植 pi {@code packages/ai/src/utils/provider-retry.ts}：SDK 以
 * {@code maxRetries: 0} 调用时，由本类独占 provider/SDK 传输层重试。
 *
 * <p>判据镜像钉版 OpenAI/Anthropic SDK 的内置重试（{@code x-should-retry}
 * 短路、408/409/429/5xx、Retry-After 头）；服务器要求的延迟超过
 * {@code maxRetryDelayMs} 立即硬失败（默认 60 秒，设 0 关闭）。
 *
 * <p>与 pi 的唯一偏差（R5）：退避睡眠不支持 AbortSignal 中断——车道层没有
 * 信号可传，协作式中止由宿主层承担。
 */
public final class ProviderRetry {

    /** pi provider-retry.ts:1 的默认 cap。 */
    public static final long DEFAULT_MAX_RETRY_DELAY_MS = 60_000L;

    private ProviderRetry() {}

    /**
     * pi {@code ProviderRetryOptions}（去掉 signal，R5）。
     *
     * @param maxRetries      null ≙ pi 的 undefined ⇒ 0
     * @param maxRetryDelayMs null ≙ 60000；0 ⇒ 关闭 cap
     */
    public record Options(Integer maxRetries, Long maxRetryDelayMs) {

        public Options(int maxRetries) {
            this(maxRetries, DEFAULT_MAX_RETRY_DELAY_MS);
        }
    }

    /** 携带 status + 可选 headers 的错误形状（pi {@code ProviderError}）。 */
    public record ProviderFailure(
            String message,
            Integer status,
            Map<String, List<String>> headers,
            Throwable cause) {}

    /** 服务器要求的重试延迟超过 cap（pi 抛普通 Error，此处给专属类型便于上层识别）。 */
    public static final class RetryDelayTooLongException extends RuntimeException {
        public RetryDelayTooLongException(String message) {
            super(message);
        }
    }

    /** 退避睡眠出口（生产用 {@link Thread#sleep}，测试注入记录式实现）。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    public static <T> T retry(
            Supplier<T> request,
            Function<? super Throwable, ? extends ProviderFailure> adapt,
            Options options) {
        return retry(request, adapt, options, Thread::sleep);
    }

    /**
     * pi {@code retryProviderRequest} 循环（:105-125）。每次重试都是一次全新
     * SDK 请求，{@code X-Stainless-Retry-Count} 恒为 0。
     */
    static <T> T retry(
            Supplier<T> request,
            Function<? super Throwable, ? extends ProviderFailure> adapt,
            Options options,
            Sleeper sleeper) {
        int maxRetries = options.maxRetries() == null ? 0 : options.maxRetries();
        int retriesRemaining = maxRetries;

        for (;;) {
            try {
                return request.get();
            } catch (RuntimeException error) {
                if (retriesRemaining <= 0) {
                    throw error;
                }
                ProviderFailure failure = adapt.apply(error);
                if (failure == null || !isRetryable(failure)) {
                    throw error;
                }

                int retryIndex = maxRetries - retriesRemaining;
                retriesRemaining--;
                long delayMs = retryDelayMs(
                        failure, retryIndex, options.maxRetryDelayMs());
                try {
                    sleeper.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Provider retry wait interrupted", e);
                }
            }
        }
    }

    /** pi {@code isRetryableProviderError}（:23-35）。 */
    static boolean isRetryable(ProviderFailure error) {
        String shouldRetry = firstHeader(error.headers(), "x-should-retry");
        if ("true".equals(shouldRetry)) {
            return true;
        }
        if ("false".equals(shouldRetry)) {
            return false;
        }

        if (error.status() == null) {
            return true;
        }
        int status = error.status();
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    /** pi {@code getRetryDelayMs}（:51-67）。 */
    static long retryDelayMs(ProviderFailure error, int retryIndex, Long maxRetryDelayMs) {
        long capMs = maxRetryDelayMs == null ? DEFAULT_MAX_RETRY_DELAY_MS : maxRetryDelayMs;

        String retryAfterMs = firstHeader(error.headers(), "retry-after-ms");
        if (retryAfterMs != null) {
            try {
                return validateServerRetryDelayMs(
                        (long) Double.parseDouble(retryAfterMs), capMs, error.message());
            } catch (NumberFormatException ignored) {
                // pi Number.isNaN ⇒ 落到下一格里。
            }
        }

        String retryAfter = firstHeader(error.headers(), "retry-after");
        if (retryAfter != null) {
            long delayMs;
            try {
                delayMs = (long) (Double.parseDouble(retryAfter) * 1000);
            } catch (NumberFormatException e) {
                delayMs = Instant.now().until(
                        ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant(),
                        java.time.temporal.ChronoUnit.MILLIS);
            }
            return validateServerRetryDelayMs(delayMs, capMs, error.message());
        }

        double exponentialDelay = Math.min(0.5 * Math.pow(2, retryIndex), 8) * 1000;
        return (long) (exponentialDelay * (1 - Math.random() * 0.25));
    }

    /** pi {@code validateServerRetryDelayMs}（:37-49）。 */
    static long validateServerRetryDelayMs(long delayMs, long maxRetryDelayMs, String providerErrorMessage) {
        if (maxRetryDelayMs > 0 && delayMs > maxRetryDelayMs) {
            throw new RetryDelayTooLongException(String.format(
                    "Server requested %ds retry delay (max: %ds). %s",
                    (long) Math.ceil(delayMs / 1000.0),
                    (long) Math.ceil(maxRetryDelayMs / 1000.0),
                    providerErrorMessage));
        }
        return delayMs;
    }

    /**
     * HTTP 头名大小写不敏感取值（pi {@code Headers.get} 语义）；适配层产出的
     * Map 若本身大小写不敏感，此扫描同样安全。
     */
    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)
                    && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
