package com.pijava.ai.utils;

import java.util.List;
import java.util.regex.Pattern;

import com.pijava.ai.message.Message;

/**
 * 瞬断错误白名单判据 —— pi {@code packages/ai/src/utils/retry.ts} 的
 * {@code isRetryableAssistantError}（:235-240）与两张拼接正则表（:7-24、:26-90）
 * 的逐条移植（package 3d，{@code docs/31 §8.22}）。与 {@link ContextOverflow}
 * 同层同文件位：哪些错误文本是「值得再试一次」的瞬断，是 **provider 响应形状**
 * 的知识，不是循环策略。
 *
 * <p><b>白名单，默认不重试</b>：{@code stopReason === "error"} ∧
 * {@code errorMessage} 非空 ∧ <b>不</b>命中配额排除表 ∧ <b>命中</b>瞬断白名单
 * ⇒ true。errorMessage 缺失/空串（pi 的 falsy）⇒ false —— 与 pi-java 3d 之前
 * 宿主层「null ⇒ true」的黑名单形状<b>正好反转</b>。</p>
 *
 * <p><b>Java 方言映射</b>：pi 两表都是
 * {@code new RegExp(patterns.join("|"), "i")} ⇒ 这里同样以 {@code |} 拼接、
 * {@code CASE_INSENSITIVE}；JS {@code test()} 是非锚定搜索 ⇒
 * {@code Matcher.find()}（与 {@link ContextOverflow} 同一映射）。
 * pi 的 {@code !message.errorMessage} 把空串也判 falsy ⇒ Java
 * {@code null || isEmpty()}。</p>
 *
 * <p>本类只回答「分类」；预算、退避、事件与续跑归 harness 的
 * {@code PostRunRetry} 与摘要重试路（pi 注释原文：This does not implement
 * retry policy）。</p>
 */
public final class RetryableError {

    /**
     * pi {@code NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN}（retry.ts:7-24）：
     * 订阅/账户/预算耗尽类文案 —— 是配额墙，不是瞬态节流，重试只会空耗。
     */
    private static final Pattern NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN =
        buildProviderErrorPattern(List.of(
            // OpenCode Go/free-tier limits returned as 429 JSON error types by OpenCode's
            // Zen API. These are subscription/account limits, not transient throttles.
            "GoUsageLimitError",
            "FreeUsageLimitError",

            // OpenCode Go subscription-limit text asks users to enable available-balance
            // usage after rolling/weekly/monthly limits are reached.
            "Monthly usage limit reached",
            "available balance",

            // Generic quota/budget/billing exhaustion. `insufficient_quota` is OpenAI's
            // quota/billing error code; the other strings cover common gateway wording.
            "insufficient_quota",
            "out of budget",
            "quota exceeded",
            "billing"
        ));

    /**
     * pi {@code RETRYABLE_PROVIDER_ERROR_PATTERN}（retry.ts:26-90）：只有命中
     * 其中之一的错误文本才值得重试。
     */
    private static final Pattern RETRYABLE_PROVIDER_ERROR_PATTERN =
        buildProviderErrorPattern(List.of(
            // Generic provider load, HTTP status, and server-side transient failures.
            "overloaded",
            "currently experiencing high demand", // retry.ts:29（紧跟 overloaded）
            "rate.?limit",
            "too many requests",
            "429",
            "500",
            "502",
            "503",
            "504",
            "520", // retry.ts:37（插在 504 与 524 之间）
            "524",
            "service.?unavailable",
            "server.?error",
            "internal.?error",

            // Wrapper/provider text for transient upstream failures, including OpenRouter
            // "Provider returned error" responses (#2264).
            "provider.?returned.?error",
            "exceeded request buffer limit while retrying upstream",

            // Network, proxy, and fetch transport failures. This includes OpenAI Codex
            // raw-fetch failures such as "upstream connect", "connection refused", and
            // "reset before headers" (#733), plus OpenRouter connection drops (#3317).
            "network.?error",
            "connection.?error",
            "connection.?refused",
            "connection.?lost",
            "other side closed",
            "fetch failed",
            "getaddrinfo",
            "ENOTFOUND",
            "EAI_AGAIN",
            "upstream.?connect",
            "reset before headers",
            "socket hang up",
            "socket connection was closed",
            "timed? out",
            "timeout",
            "terminated",

            // WebSocket transports can report close/error text instead of HTTP/fetch text.
            "websocket.?closed",
            "websocket.?error",

            // Premature stream endings from SDKs and transports. Anthropic can throw
            // "stream ended without ..." and "Anthropic stream ended before message_stop"
            // (#4433); Bedrock/Smithy can throw an HTTP/2 no-response error (#3594).
            "ended without",
            "stream ended before message_stop",
            "stream ended before a terminal response event",
            "http2 request did not get a response",

            // Provider-requested retry delay cap failures should flow through the outer
            // retry policy so callers can surface/abort the backoff (#1123).
            "retry delay",

            // Explicit retry guidance emitted mid-stream by OpenAI Responses and Bedrock
            // stream exceptions (#6019).
            "you can retry your request",
            "try your request again",
            "please retry your request",

            // gRPC based providers (e.g. NVIDIA NIM)
            "ResourceExhausted"
        ));

    private static Pattern buildProviderErrorPattern(List<String> patterns) {
        return Pattern.compile(String.join("|", patterns), Pattern.CASE_INSENSITIVE);
    }

    private RetryableError() {}

    /**
     * pi {@code isRetryableAssistantError(message)}（retry.ts:235-240）。
     *
     * @param message 终局助手消息；非 error 收尾、errorMessage 空、命中配额表、
     *                未命中瞬断表 ⇒ 一律 {@code false}
     */
    public static boolean isRetryableAssistantError(Message.AssistantMessage message) {
        if (!"error".equals(message.stopReason()) || message.errorMessage() == null
                || message.errorMessage().isEmpty()) {
            return false;
        }
        String errorMessage = message.errorMessage();
        if (NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN.matcher(errorMessage).find()) {
            return false;
        }
        return RETRYABLE_PROVIDER_ERROR_PATTERN.matcher(errorMessage).find();
    }
}
