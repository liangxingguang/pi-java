package com.pijava.ai.utils;

import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code packages/ai/src/utils/retry.ts} 白名单判据的移植哨兵（package 3d，
 * {@code 原 docs/31 §8.22}）。表驱动按 retry.ts 的注释分组取样：每组至少一条命中、
 * 配额排除表逐条、以及「默认不重试」的三个入口（非 error / null / 空串）。
 */
class RetryableErrorTest {

    private static Message.AssistantMessage error(String message) {
        return new Message.AssistantMessage(List.<ContentBlock>of(), StopReason.ERROR, null,
            null, null, null, null, null, message, null);
    }

    // ═══════════════════════════════════════════════════════════
    // 白名单命中（retry.ts:26-90，按注释分组取样）
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "The model is currently overloaded",
        // pi retry.ts:33-34（8b5708dbb）：server-busy 瞬断两种文案。
        "server_busy",
        "The servers are currently busy, please try again",
        // pi 白名单里紧跟 "overloaded" 的那条（retry.ts:35）—— 移植时曾整条漏掉
        // （原 docs/43 包A0 步6）：高需求文案不重试、直接失败。
        "The model is currently experiencing high demand, please try again later",
        // pi 白名单里紧跟 high demand 的那条（retry.ts:34）。
        "model is at capacity",
        "Rate limit reached for gpt-5",
        "Too many requests",
        "429 status code",
        "500 Internal Server Error",
        "502 Bad Gateway",
        "503 Service Unavailable",
        "504 Gateway Timeout",
        // pi 白名单里插在 504 与 524 之间的那条（retry.ts:37）—— 同批漏掉的一条；
        // ⚠️ 子串匹配（pi 的既有语义，照抄不收紧）：凡含 "520" 的文本都会命中。
        "520 status code",
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
        // pi retry.ts:88（5b6c792b4）：Node ERR_HTTP2_STREAM_CANCEL，HTTP/2 会话先死
        // （Bedrock SDK 5 分钟会话超时，#10379）。
        "pending stream has been canceled",
        "retry delay exceeds cap",
        "you can retry your request",
        "try your request again",
        "please retry your request",
        "ResourceExhausted. Failed, hit resource quota",
        // ChatGPT 订阅路的中流失败（retry.ts:100-101）—— 无 HTTP 503 也要重试。
        "subscription_sharing_usage_unavailable",
        "subscription_sharing_user_unavailable",
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
        // ChatGPT 订阅路的配额墙（retry.ts:27）：同 subscription_sharing_ 家族，
        // 但裁决与两条 _unavailable 相反 —— 见 sharingFamilySplitsBetweenTheTwoTables。
        "subscription_sharing_usage_limit_exceeded",
        // 组合排除：既撞配额又撞白名单 ⇒ 配额赢
        "quota exceeded (429)",
        // ⚠️ 同族的配额墙单独不放电流（其文本不含任何白名单子串）⇒ 必须与白名单词
        // 组合才能证明「它在排除表里」。缺此行则删掉排除表那条**零红**（M4 实测）。
        "429 subscription_sharing_usage_limit_exceeded"
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
    @EnumSource(mode = EnumSource.Mode.INCLUDE,
        names = {"STOP", "LENGTH", "TOOL_USE", "ABORTED"})
    void nonErrorStopReasonsAreNotRetryable(StopReason stopReason) {
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

    // ═══════════════════════════════════════════════════════════
    // 同族相反裁决（retry.ts:27 vs :100-101）：同前缀、两张表
    // ═══════════════════════════════════════════════════════════

    /**
     * `subscription_sharing_usage_limit_exceeded`（配额墙，不重试）与
     * `subscription_sharing_usage_unavailable`／`subscription_sharing_user_unavailable`
     * （瞬断，重试）**前缀相同、裁决相反**。全塞进任一张表都会翻转其中一半 ⇒
     * 单表实现必红。
     */
    @Test
    void sharingFamilySplitsBetweenTheTwoTables() {
        assertThat(RetryableError.isRetryableAssistantError(
            error("subscription_sharing_usage_limit_exceeded")))
            .as("同族的配额墙：必须判不重试")
            .isFalse();
        assertThat(RetryableError.isRetryableAssistantError(
            error("subscription_sharing_usage_unavailable")))
            .as("同族的瞬断：必须判重试")
            .isTrue();
        assertThat(RetryableError.isRetryableAssistantError(
            error("subscription_sharing_user_unavailable")))
            .as("同族的瞬断：必须判重试")
            .isTrue();
    }
}
