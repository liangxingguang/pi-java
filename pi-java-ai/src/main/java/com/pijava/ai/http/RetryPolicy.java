package com.pijava.ai.http;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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

    private RetryPolicy(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.baseDelay = builder.baseDelay;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.maxDelay = builder.maxDelay;
        this.retryableStatuses = Set.copyOf(builder.retryableStatuses);
    }

    /** Default policy: up to 3 retries, exponential backoff, 5xx codes. */
    public static RetryPolicy defaultPolicy() {
        return new Builder().build();
    }

    /** Whether the given HTTP status code is retryable. */
    public boolean shouldRetry(int statusCode) {
        return retryableStatuses.contains(statusCode) || (statusCode >= 500 && statusCode < 600);
    }

    /** Whether the given exception is retryable (IO/timeout errors are). */
    public boolean shouldRetry(Exception e) {
        return e instanceof java.io.IOException
                || e instanceof java.util.concurrent.TimeoutException;
    }

    /** Compute the delay before the next retry attempt. */
    public long delayMs(int statusCode, int attempt, HttpResponse<?> response) {
        // Respect Retry-After header if present
        if (response != null && (statusCode == 429 || statusCode == 503)) {
            var retryAfter = response.headers().firstValue("Retry-After");
            if (retryAfter.isPresent()) {
                String val = retryAfter.get();
                // Try as seconds integer
                var matcher = RETRY_AFTER_DIGIT.matcher(val);
                if (matcher.matches()) {
                    return Long.parseLong(val) * 1000;
                }
                // Try as HTTP-date (parse simply)
                try {
                    long dateMs = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                            .parse(val, java.time.ZonedDateTime::from)
                            .toInstant()
                            .toEpochMilli();
                    long delta = dateMs - System.currentTimeMillis();
                    return Math.max(0, delta);
                } catch (Exception ignored) {
                    // Fall through to backoff
                }
            }
        }
        // Exponential backoff
        long delay = (long) (baseDelay.toMillis() * Math.pow(backoffMultiplier, attempt));
        return Math.min(delay, maxDelay.toMillis());
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

        public Builder maxRetries(int n) { this.maxRetries = n; return this; }
        public Builder baseDelay(Duration d) { this.baseDelay = d; return this; }
        public Builder backoffMultiplier(double m) { this.backoffMultiplier = m; return this; }
        public Builder maxDelay(Duration d) { this.maxDelay = d; return this; }
        public Builder retryableStatuses(Set<Integer> s) { this.retryableStatuses = s; return this; }

        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
