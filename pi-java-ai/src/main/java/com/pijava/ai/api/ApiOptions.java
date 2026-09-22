package com.pijava.ai.api;

import java.time.Duration;
import java.util.Map;

/**
 * Common options for LLM API calls.
 *
 * @param baseUrl       override the default API base URL (or empty for default)
 * @param apiKey        the API key (or empty to resolve from the environment)
 * @param timeout       HTTP request timeout
 * @param maxRetries    maximum number of retries on transient failures
 * @param extra         provider-specific options
 * @param authKind      the credential's **shape**（包 A0，{@code docs/43 D5}）；决定车道把它
 *                      放在哪个头里（{@code x-api-key} ／ {@code Authorization: Bearer}）。
 *                      由凭证解析层（{@code auth.Credentials}）定下，不在车道里嗅探字符串
 */
public record ApiOptions(
    String baseUrl,
    String apiKey,
    Duration timeout,
    int maxRetries,
    Map<String, Object> extra,
    AuthKind authKind
) {
    /** Compact constructor that defensively copies the {@code extra} options map. */
    public ApiOptions {
        extra = Map.copyOf(extra);
        authKind = authKind == null ? AuthKind.API_KEY : authKind;
    }

    /**
     * 五组件构造器（存量调用点）：kind 取默认 {@link AuthKind#API_KEY} —— 行为与加该组件之前
     * 完全一致（{@code docs/43 D5} 的机械改动面）。
     */
    public ApiOptions(String baseUrl, String apiKey, Duration timeout, int maxRetries,
                      Map<String, Object> extra) {
        this(baseUrl, apiKey, timeout, maxRetries, extra, AuthKind.API_KEY);
    }

    /** Reasonable defaults for interactive use. */
    public static ApiOptions defaults() {
        return new ApiOptions("", "", Duration.ofSeconds(120), 2, Map.of(), AuthKind.API_KEY);
    }
}
