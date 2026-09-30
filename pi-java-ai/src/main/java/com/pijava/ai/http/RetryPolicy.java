package com.pijava.ai.http;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Retry policy for HTTP requests.
 *
 * <p>Determines which status codes and exceptions are retryable,
 * and computes the delay before each retry attempt.</p>
 */
public final class RetryPolicy {

    private static final Set<Integer> DEFAULT_RETRYABLE_STATUSES =
            Set.of(408, 409, 429);
    private static final Pattern RETRY_AFTER_DIGIT = Pattern.compile("\\d+");

    private final int maxRetries;
    private final Duration baseDelay;
    private final double backoffMultiplier;
    private final Duration maxDelay;
    private final Set<Integer> retryableStatuses;

    /**
     * A-14：服务器请求延迟的硬上限；null ≙ pi 默认 60s，{@link Duration#ZERO}
     * 关闭。
     */
    private final Duration maxRetryDelayMs;

    private RetryPolicy(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.baseDelay = builder.baseDelay;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.maxDelay = builder.maxDelay;
        this.retryableStatuses = Set.copyOf(builder.retryableStatuses);
        this.maxRetryDelayMs = builder.maxRetryDelayMs;
    }

    /** Default policy: up to 3 retries, exponential backoff, 5xx codes. */
    public static RetryPolicy defaultPolicy() {
        return new Builder().build();
    }

    /** Anthropic preset: 3 retries, 1000ms×2^n, retry on 429/5xx. */
    public static RetryPolicy anthropic() {
        return new Builder()
                .maxRetries(3)
                .baseDelay(Duration.ofSeconds(1))
                .retryableStatuses(Set.of(429, 500, 502, 503, 504))
                .build();
    }

    /** OpenAI preset: 5 retries, 500ms×2^n, retry on 429/500/502/503. */
    public static RetryPolicy openai() {
        return new Builder()
                .maxRetries(5)
                .baseDelay(Duration.ofMillis(500))
                .retryableStatuses(Set.of(429, 500, 502, 503))
                .build();
    }

    /** Google preset: 3 retries, 2000ms×1.5^n, retry on 429/500/503. */
    public static RetryPolicy google() {
        return new Builder()
                .maxRetries(3)
                .baseDelay(Duration.ofSeconds(2))
                .backoffMultiplier(1.5)
                .retryableStatuses(Set.of(429, 500, 503))
                .build();
    }

    /** Mistral preset: 3 retries, 1000ms×2^n, retry on 429/500/502/503. */
    public static RetryPolicy mistral() {
        return new Builder()
                .maxRetries(3)
                .baseDelay(Duration.ofSeconds(1))
                .retryableStatuses(Set.of(429, 500, 502, 503))
                .build();
    }

    /** DeepSeek preset: 3 retries, 1000ms×2^n, retry on 429/500/502/503. */
    public static RetryPolicy deepseek() {
        return new Builder()
                .maxRetries(3)
                .baseDelay(Duration.ofSeconds(1))
                .retryableStatuses(Set.of(429, 500, 502, 503))
                .build();
    }

    /** Whether the given HTTP status code is retryable (no response headers available). */
    public boolean shouldRetry(int statusCode) {
        return shouldRetry(statusCode, null);
    }

    /**
     * A-14：pi {@code isRetryableProviderError}：响应在则先读
     * {@code x-should-retry}（true/false 短路），其余走状态集合判据。
     */
    public boolean shouldRetry(int statusCode, HttpResponse<?> response) {
        if (response != null) {
            var shouldRetry = response.headers().firstValue("x-should-retry");
            if (shouldRetry.isPresent()) {
                if ("true".equalsIgnoreCase(shouldRetry.get())) {
                    return true;
                }
                if ("false".equalsIgnoreCase(shouldRetry.get())) {
                    return false;
                }
            }
        }
        return retryableStatuses.contains(statusCode) || (statusCode >= 500 && statusCode < 600);
    }

    /** Whether the given exception is retryable (IO/timeout errors are). */
    public boolean shouldRetry(Exception e) {
        return e instanceof java.io.IOException
                || e instanceof java.util.concurrent.TimeoutException;
    }

    /**
     * Compute the delay before the next retry attempt.
     *
     * @param providerMessage the provider's error message (pi 拼在 cap 文案后缀)
     */
    public long delayMs(int statusCode, int attempt, HttpResponse<?> response,
                        String providerMessage) {
        long capMs = maxRetryDelayMs == null
                ? ProviderRetry.DEFAULT_MAX_RETRY_DELAY_MS : maxRetryDelayMs.toMillis();

        // A-14：retry-after-ms 任意可重试状态都读（pi :52）。
        if (response != null) {
            var retryAfterMs = response.headers().firstValue("retry-after-ms");
            if (retryAfterMs.isPresent()) {
                try {
                    return validateServerRetryDelayMs(
                            (long) Double.parseDouble(retryAfterMs.get()), capMs, providerMessage);
                } catch (NumberFormatException ignored) {
                    // pi Number.isNaN ⇒ 落到 retry-after。
                }
            }

            var retryAfter = response.headers().firstValue("Retry-After");
            if (retryAfter.isPresent()) {
                String val = retryAfter.get();
                long delayMs;
                var matcher = RETRY_AFTER_DIGIT.matcher(val);
                if (matcher.matches()) {
                    delayMs = Long.parseLong(val) * 1000;
                } else {
                    // HTTP-date；R8：照 pi 不夹负值，负延迟等效立即重试。
                    delayMs = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                            .parse(val, java.time.ZonedDateTime::from)
                            .toInstant()
                            .toEpochMilli() - System.currentTimeMillis();
                }
                return validateServerRetryDelayMs(delayMs, capMs, providerMessage);
            }
        }

        // Exponential backoff
        long delay = (long) (baseDelay.toMillis() * Math.pow(backoffMultiplier, attempt));
        return Math.min(delay, maxDelay.toMillis());
    }

    /** 兼容旧调用（无 provider message 后缀）。 */
    public long delayMs(int statusCode, int attempt, HttpResponse<?> response) {
        return delayMs(statusCode, attempt, response, "");
    }

    /** A-14：pi {@code validateServerRetryDelayMs}（cap>0 且超限 ⇒ 硬失败）。 */
    static long validateServerRetryDelayMs(long delayMs, long capMs, String providerMessage) {
        if (capMs > 0 && delayMs > capMs) {
            String suffix = providerMessage == null || providerMessage.isEmpty()
                    ? "" : ". " + providerMessage;
            throw new ProviderRetry.RetryDelayTooLongException(String.format(
                    "Server requested %ds retry delay (max: %ds)%s",
                    (long) Math.ceil(delayMs / 1000.0),
                    (long) Math.ceil(capMs / 1000.0),
                    suffix));
        }
        return delayMs;
    }

    /** Number of retry attempts allowed. */
    public int maxRetries() {
        return maxRetries;
    }

    // ── Builder ────────────────────────────────────────────────

    public static final class Builder {
        private int maxRetries = 3;
        private Duration baseDelay = Duration.ofSeconds(1);
        private double backoffMultiplier = 2.0;
        private Duration maxDelay = Duration.ofSeconds(60);
        private Set<Integer> retryableStatuses = DEFAULT_RETRYABLE_STATUSES;
        private Duration maxRetryDelayMs;

        public Builder maxRetries(int n) { this.maxRetries = n; return this; }
        public Builder baseDelay(Duration d) { this.baseDelay = d; return this; }
        public Builder backoffMultiplier(double m) { this.backoffMultiplier = m; return this; }
        public Builder maxDelay(Duration d) { this.maxDelay = d; return this; }
        public Builder retryableStatuses(Set<Integer> s) { this.retryableStatuses = s; return this; }

        /** A-14：服务器请求延迟上限（null ⇒ 60s，零 ⇒ 关闭）。 */
        public Builder maxRetryDelayMs(Duration d) { this.maxRetryDelayMs = d; return this; }

        /** Build the {@link RetryPolicy} instance. */
        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
