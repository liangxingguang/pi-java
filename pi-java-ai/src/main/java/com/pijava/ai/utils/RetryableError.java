package com.pijava.ai.utils;

import java.util.List;
import java.util.regex.Pattern;

import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

/**
 * 瞬断错误白名单判据 —— pi {@code packages/ai/src/utils/retry.ts} 的
 * {@code isRetryableAssistantError}（:235-240）与两张拼接正则表（:7-24、:26-90）
 * 的逐条移植（package 3d，{@code 原 docs/31 §8.22}）。与 {@link ContextOverflow}
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
            "billing",

            // Sign in with ChatGPT: the subscription's shared usage limit, which resets
            // after hours rather than seconds.（retry.ts:27）
            "subscription_sharing_usage_limit_exceeded",
            // Sign in with ChatGPT on a ChatGPT Business plan: a usage limit set by the
            // workspace admin. Only the admin can raise it.（retry.ts:28-30）
            "subscription_sharing_workplace_individual_usage_limit_exceeded",

            // Zhipu/Z.ai (open.bigmodel.cn) error code 1308: the plan's usage limit is
            // reached, sent as HTTP 429. It resets after hours, not seconds (#11537).
            "\"code\"\\s*:\\s*\"?1308(?![0-9])"
        ));

    /**
     * pi {@code RETRYABLE_PROVIDER_ERROR_PATTERN}（retry.ts:26-90）：只有命中
     * 其中之一的错误文本才值得重试。
     */
    private static final Pattern RETRYABLE_PROVIDER_ERROR_PATTERN =
        buildProviderErrorPattern(List.of(
            // Generic provider load, HTTP status, and server-side transient failures.
            "overloaded",
            "server_busy", // retry.ts:33（8b5708dbb：server-busy 瞬断）
            "servers are currently busy", // retry.ts:34
            "currently experiencing high demand", // retry.ts:35（紧跟 high demand 组）
            "model is at capacity", // retry.ts:36
            "rate.?limit",
            "too many requests",
            // Status codes must not be part of a longer word or number, e.g. a request id or byte count (#11057).
            "(?<![a-z0-9])(?:429|500|502|503|504|520|524)(?![a-z0-9])", // retry.ts:46-47
            // Also matches "The service is temporarily unavailable" (#10965).
            "service.?(?:is.?)?(?:temporarily.?)?unavailable",
            "server.?error",
            "internal.?error",

            // Wrapper/provider text for transient upstream failures, including OpenRouter
            // "Provider returned error" responses (#2264) and gateway wording such as
            // "The upstream provider returned an error" (#10842).
            "provider.?returned.?(?:an.?)?error",
            "exceeded request buffer limit while retrying upstream",
            // Gateway failure relaying the upstream response stream, sent as an OpenAI
            // Responses `response.failed` event without an error code (#10920).
            "unable to forward upstream",
            // Other gateway upstream failures relayed as `response.failed` error codes (#10920).
            "upstream.?request.?failed",
            "upstream_stream_read_error",
            "stream was interrupted",
            "http/?2 stream failed",

            // Network, proxy, and fetch transport failures. This includes OpenAI Codex
            // raw-fetch failures such as "upstream connect", "connection refused", and
            // "reset before headers" (#733), plus OpenRouter connection drops (#3317).
            "network.?error",
            "connection.?error",
            "connection.?refused",
            "connection.?lost",
            "other side closed",
            "fetch failed",
            // node-fetch system errors, e.g. the Google ADC OAuth token refresh used by
            // google-vertex. The reason is often empty (#10996).
            "request to \\S+ failed, reason:",
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
            // Codex CLI mid-stream disconnect, relayed by proxies that wrap the Codex CLI,
            // e.g. "stream disconnected before completion: stream closed before response.completed" (#11057).
            "stream disconnected before completion",
            // Node ERR_HTTP2_STREAM_CANCEL：HTTP/2 会话在请求发出前死掉
            // （Bedrock SDK 5 分钟会话超时，#10379）。retry.ts:88（5b6c792b4）
            "pending stream has been canceled",

            // Provider-requested retry delay cap failures should flow through the outer
            // retry policy so callers can surface/abort the backoff (#1123).
            "retry delay",

            // Explicit retry guidance emitted mid-stream by OpenAI Responses and Bedrock
            // stream exceptions (#6019).
            "you can retry your request",
            "try your request again",
            "please retry your request",

            // gRPC based providers (e.g. NVIDIA NIM)
            "ResourceExhausted",

            // Sign in with ChatGPT: usage or user data temporarily unavailable. Usage
            // failures can arrive mid-stream without an HTTP 503 in the message.（retry.ts:100-101）
            "subscription_sharing_usage_unavailable",
            "subscription_sharing_user_unavailable"
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
        if (message.stopReason() != StopReason.ERROR || message.errorMessage() == null
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
