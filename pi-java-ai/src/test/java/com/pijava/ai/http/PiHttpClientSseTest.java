package com.pijava.ai.http;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SSE parsing logic and {@link RetryPolicy}.
 */
class PiHttpClientSseTest {

    // SSE parsing is tested indirectly through PiHttpClient.SseIterator.
    // The iterator is package-private, so we test the retry policy and
    // the ServerSentEvent record directly.

    @Test
    void serverSentEventShouldHoldFields() {
        var sse = new PiHttpClient.ServerSentEvent("1", "message", "hello world");

        assertThat(sse.id()).isEqualTo("1");
        assertThat(sse.event()).isEqualTo("message");
        assertThat(sse.data()).isEqualTo("hello world");
    }

    @Test
    void emptyServerSentEvent() {
        assertThat(PiHttpClient.ServerSentEvent.EMPTY.id()).isEmpty();
        assertThat(PiHttpClient.ServerSentEvent.EMPTY.event()).isEmpty();
        assertThat(PiHttpClient.ServerSentEvent.EMPTY.data()).isEmpty();
    }

    // ── RetryPolicy tests ──────────────────────────────────

    @Test
    void retryPolicyShouldRetryOn429() {
        var policy = RetryPolicy.defaultPolicy();
        assertThat(policy.shouldRetry(429)).isTrue();
    }

    @Test
    void retryPolicyShouldRetryOn5xx() {
        var policy = RetryPolicy.defaultPolicy();
        assertThat(policy.shouldRetry(500)).isTrue();
        assertThat(policy.shouldRetry(502)).isTrue();
        assertThat(policy.shouldRetry(503)).isTrue();
    }

    @Test
    void retryPolicyShouldNotRetryOn4xx() {
        var policy = RetryPolicy.defaultPolicy();
        assertThat(policy.shouldRetry(400)).isFalse();
        assertThat(policy.shouldRetry(401)).isFalse();
        assertThat(policy.shouldRetry(403)).isFalse();
        assertThat(policy.shouldRetry(404)).isFalse();
    }

    @Test
    void retryPolicyShouldNotRetryOn2xx() {
        var policy = RetryPolicy.defaultPolicy();
        assertThat(policy.shouldRetry(200)).isFalse();
        assertThat(policy.shouldRetry(201)).isFalse();
    }

    @Test
    void retryPolicyShouldRespectMaxRetries() {
        var policy = new RetryPolicy.Builder().maxRetries(3).build();
        assertThat(policy.maxRetries()).isEqualTo(3);
    }

    @Test
    void retryPolicyShouldCalculateDelay() {
        var policy = RetryPolicy.defaultPolicy();
        long delay = policy.delayMs(429, 1, null);

        // Should be > 0 with exponential backoff
        assertThat(delay).isPositive();
    }

    @Test
    void defaultRetryPolicyMaxRetries() {
        var policy = RetryPolicy.defaultPolicy();
        assertThat(policy.maxRetries()).isPositive();
    }

    @Test
    void retryPolicyShouldRetryOnIoException() {
        var policy = RetryPolicy.defaultPolicy();
        assertThat(policy.shouldRetry(new java.io.IOException("timeout"))).isTrue();
    }

    @Test
    void retryPolicyShouldNotRetryOnRuntimeException() {
        var policy = RetryPolicy.defaultPolicy();
        assertThat(policy.shouldRetry(new RuntimeException("boom"))).isFalse();
    }
}
