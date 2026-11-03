package com.pijava.ai.api;

import java.time.Duration;
import java.util.Map;

import com.pijava.ai.identity.ClientIdentity;
import com.pijava.telemetry.TelemetrySpan;

/**
 * Common options for LLM API calls.
 *
 * @param baseUrl       override the default API base URL (or empty for default)
 * @param apiKey        the API key (or empty to resolve from the environment)
 * @param timeout       HTTP request timeout
 * @param maxRetries    maximum number of retries on transient failures
 * @param extra         provider-specific options
 * @param authKind      the credential's **shape**（包 A0，{@code 原 docs/43 D5}）；决定车道把它
 *                      放在哪个头里（{@code x-api-key} ／ {@code Authorization: Bearer}）。
 *                      由凭证解析层（{@code auth.Credentials}）定下，不在车道里嗅探字符串
 * @param telemetry     docs/13 B263：本逻辑请求的**显式父跨度**（pi
 *                      {@code ProviderRequestOptions.telemetry}，{@code types.ts:160-161}）。
 *                      {@code null} ≙ pi 的 {@code undefined} ⇒ 不产生 {@code pi.ai.*}
 *                      遥测。只由调用方显式传入，宿主是否接线不归本字段决定。
 * @param identity      docs/14 B266：本请求的客户端身份（pi
 *                      {@code ProviderRequestOptions.identity}，{@code types.ts:196-201}）。
 *                      {@code null} ≙ pi 的 {@code undefined} ⇒ 车道仍发默认 detailed
 *                      {@code User-Agent}，只是没有归因头。
 */
public record ApiOptions(
    String baseUrl,
    String apiKey,
    Duration timeout,
    int maxRetries,
    Map<String, Object> extra,
    AuthKind authKind,
    @org.jspecify.annotations.Nullable TelemetrySpan telemetry,
    @org.jspecify.annotations.Nullable ClientIdentity identity
) {
    /** Compact constructor that defensively copies the {@code extra} options map. */
    public ApiOptions {
        extra = Map.copyOf(extra);
        authKind = authKind == null ? AuthKind.API_KEY : authKind;
    }

    /**
     * 七组件构造器（docs/13 的**规范**构造）：{@code identity} 缺席 ≙
     * pi 的 {@code undefined}。保留该形态使 B266 之前的调用点零改签。
     */
    public ApiOptions(String baseUrl, String apiKey, Duration timeout, int maxRetries,
                      Map<String, Object> extra, AuthKind authKind,
                      @org.jspecify.annotations.Nullable TelemetrySpan telemetry) {
        this(baseUrl, apiKey, timeout, maxRetries, extra, authKind, telemetry, null);
    }

    /**
     * 六组件构造器（docs/13 之前的**规范**构造）：{@code telemetry} 与 {@code identity}
     * 缺席 ≙ pi 的 {@code undefined}。
     */
    public ApiOptions(String baseUrl, String apiKey, Duration timeout, int maxRetries,
                      Map<String, Object> extra, AuthKind authKind) {
        this(baseUrl, apiKey, timeout, maxRetries, extra, authKind, null, null);
    }

    /**
     * 五组件构造器（存量调用点）：kind 取默认 {@link AuthKind#API_KEY} —— 行为与加该组件之前
     * 完全一致（{@code 原 docs/43 D5} 的机械改动面）。
     */
    public ApiOptions(String baseUrl, String apiKey, Duration timeout, int maxRetries,
                      Map<String, Object> extra) {
        this(baseUrl, apiKey, timeout, maxRetries, extra, AuthKind.API_KEY);
    }

    /** Reasonable defaults for interactive use. */
    public static ApiOptions defaults() {
        return new ApiOptions("", "", Duration.ofSeconds(120), 2, Map.of(),
            AuthKind.API_KEY, null, null);
    }

    /**
     * docs/14 B266：把请求级 identity 写回新选项。
     *
     * <p>编码侧唯一的第八参重建入口；coding-agent 与测试都走这里，避免手写
     * 第 12 个重建点把其它组件丢掉。</p>
     */
    public static ApiOptions withIdentity(ApiOptions options, ClientIdentity identity) {
        return new ApiOptions(options.baseUrl(), options.apiKey(), options.timeout(),
            options.maxRetries(), options.extra(), options.authKind(), options.telemetry(),
            identity);
    }
}
