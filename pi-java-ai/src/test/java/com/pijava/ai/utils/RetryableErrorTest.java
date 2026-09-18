package com.pijava.ai.utils;

import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code packages/ai/src/utils/retry.ts} 白名单判据的移植哨兵（package 3d，
 * {@code docs/31 §8.22}）。表驱动按 retry.ts 的注释分组取样：每组至少一条命中、
 * 配额排除表逐条、以及「默认不重试」的三个入口（非 error / null / 空串）。
 */
class RetryableErrorTest {

    private static Message.AssistantMessage error(String message) {
        return new Message.AssistantMessage(List.<ContentBlock>of(), "error", null,
            null, null, null, null, null, message, null);
    }

    // ═══════════════════════════════════════════════════════════
    // 白名单命中（retry.ts:26-90，按注释分组取样）
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "The model is currently overloaded",
        "Rate limit reached for gpt-5",
        "Too many requests",
        "429 status code",
        "500 Internal Server Error",
        "502 Bad Gateway",
        "503 Service Unavailable",
        "504 Gateway Timeout",
        "524 origin timeout",
        "server error, please wait",
        "Provider returned error: upstream gone",
        "exceeded request buffer limit while retrying upstream",
        "Network error while fetching",
        "connection error: eof",
        "connection refused",
        "connection lost mid-stream",
        "other side closed",
        "fetch failed",
        "getaddrinfo ENOTFOUND api.example.com",
        "EAI_AGAIN",
        "upstream connect error or disconnect/reset",
        "reset before headers",
        "socket hang up",
        "socket connection was closed unexpectedly",
        "request timed out after 60s",
        "timeout",
        "terminated",
        "websocket closed",
        "websocket error",
        "stream ended before message_stop",
        "ended without a terminal event",
        "stream ended before a terminal response event",
        "http2 request did not get a response",
        "retry delay exceeds cap",
        "you can retry your request",
        "try your request again",
        "please retry your request",
        "ResourceExhausted. Failed, hit resource quota",
        "OVERLOADED",
        "Socket HANG Up"
    })
    void transientErrorsAreRetryable(String errorMessage) {
        assertThat(RetryableError.isRetryableAssistantError(error(errorMessage))).isTrue();
    }

    // ═══════════════════════════════════════════════════════════
    // 配额排除表（retry.ts:7-24）：即便撞白名单词也不重试
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "GoUsageLimitError: plan exhausted",
        "FreeUsageLimitError",
        "Monthly usage limit reached, upgrade required",
        "enable available balance usage to continue",
        "insufficient_quota",
        "out of budget",
        "quota exceeded",
        "billing required",
        // 组合排除：既撞配额又撞白名单 ⇒ 配额赢
        "quota exceeded (429)"
    })
    void quotaLimitsAreNeverRetryable(String errorMessage) {
        assertThat(RetryableError.isRetryableAssistantError(error(errorMessage))).isFalse();
    }

    // ═══════════════════════════════════════════════════════════
    // 默认不重试的三个入口 + 非瞬断错误
    // ═══════════════════════════════════════════════════════════

    @Test
    void missingErrorMessageIsNotRetryable() {
        assertThat(RetryableError.isRetryableAssistantError(error(null))).isFalse();
        assertThat(RetryableError.isRetryableAssistantError(error(""))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "length", "toolUse", "aborted"})
    void nonErrorStopReasonsAreNotRetryable(String stopReason) {
        var message = new Message.AssistantMessage(List.<ContentBlock>of(), stopReason, null,
            null, null, null, null, null, "overloaded", null);
        assertThat(RetryableError.isRetryableAssistantError(message)).isFalse();
    }

    @Test
    void deterministicErrorsAreNotRetryable() {
        assertThat(RetryableError.isRetryableAssistantError(
            error("invalid api key provided"))).isFalse();
        assertThat(RetryableError.isRetryableAssistantError(
            error("model not found"))).isFalse();
    }
}
