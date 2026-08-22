package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionRunner 自动重试策略（pi {@code agent-session.ts} retry）。
 */
class SessionRunnerTest {

    @Test
    void contextOverflowErrorsAreNotRetryable() {
        assertThat(SessionRunner.isRetryableError("This model's maximum context length is 8192 tokens"))
            .isFalse();
        assertThat(SessionRunner.isRetryableError("prompt is too long: 10000 > 8192 tokens"))
            .isFalse();
        assertThat(SessionRunner.isRetryableError("context_length_exceeded: input too large"))
            .isFalse();
    }

    @Test
    void transientErrorsAreRetryable() {
        assertThat(SessionRunner.isRetryableError("Rate limit exceeded, please retry"))
            .isTrue();
        assertThat(SessionRunner.isRetryableError("503 Service Unavailable"))
            .isTrue();
        assertThat(SessionRunner.isRetryableError(null)).isTrue();
        assertThat(SessionRunner.isRetryableError("")).isTrue();
    }

    @Test
    void retryDelayIsExponential() {
        assertThat(SessionRunner.retryDelayMs(1)).isEqualTo(2_000);
        assertThat(SessionRunner.retryDelayMs(2)).isEqualTo(4_000);
        assertThat(SessionRunner.retryDelayMs(3)).isEqualTo(8_000);
    }
}
