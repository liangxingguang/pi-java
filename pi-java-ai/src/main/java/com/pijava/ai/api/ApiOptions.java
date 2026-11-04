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
 * @param env           docs/19 #50：provider 作用域的环境覆盖（pi
 *                      {@code ProviderRequestOptions.env}，{@code types.ts:171-176}）。
 *                      {@code null} ≙ pi 的 {@code undefined}；非 null 时覆盖进程环境，
 *                      用于地域/端点占位符/代理等 provider 配置（不是鉴权层）。
 */
public record ApiOptions(
    String baseUrl,
    String apiKey,
    Duration timeout,
    int maxRetries,
    Map<String, Object> extra,
    AuthKind authKind,
    @org.jspecify.annotations.Nullable TelemetrySpan telemetry,
    @org.jspecify.annotations.Nullable ClientIdentity identity,
    @org.jspecify.annotations.Nullable Map<String, String> env
) {
    /** Compact constructor that defensively copies the {@code extra} and {@code env} maps. */
    public ApiOptions {
        extra = Map.copyOf(extra);
        env = env == null ? null : Map.copyOf(env);
        authKind = authKind == null ? AuthKind.API_KEY : authKind;
    }

    /**
     * 八组件构造器（docs/14 的**规范**构造）：{@code env} 缺席 ≙
     * pi 的 {@code undefined}。保留该形态使 B266 之前的调用点零改签。
     */
    public ApiOptions(String baseUrl, String apiKey, Duration timeout, int maxRetries,
                      Map<String, Object> extra, AuthKind authKind,
                      @org.jspecify.annotations.Nullable TelemetrySpan telemetry,
                      @org.jspecify.annotations.Nullable ClientIdentity identity) {
        this(baseUrl, apiKey, timeout, maxRetries, extra, authKind, telemetry, identity, null);
    }

    /**
     * 七组件构造器（docs/13 的**规范**构造）：{@code identity} 缺席 ≙
     * pi 的 {@code undefined}。
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
            identity, options.env());
    }

    /**
     * docs/19 #50：把请求级 provider env 写回新选项（编码侧唯一的第九参重建入口）。
     * 其余组件逐字保真。
     */
    public static ApiOptions withEnv(ApiOptions options, Map<String, String> env) {
        return new ApiOptions(options.baseUrl(), options.apiKey(), options.timeout(),
            options.maxRetries(), options.extra(), options.authKind(), options.telemetry(),
            options.identity(), env);
    }

    /**
     * docs/20 §6.3：把 {@code extra} 整体替换为 {@code extra} 的副本。
     *
     * <p>编码侧唯一的“语义重写 extra”入口 —— 与 {@link #withIdentity}／{@link #withEnv}
     * 同法逐组件保真，避免手写九组件时丢字段（docs/19 的 15 重建点教训）。deferred
     * 取回前按 pi 剥离 {@code deferred}／{@code onResponse} 两键就走这里。</p>
     */
    public static ApiOptions withExtra(ApiOptions options, Map<String, Object> extra) {
        return new ApiOptions(options.baseUrl(), options.apiKey(), options.timeout(),
            options.maxRetries(), extra, options.authKind(), options.telemetry(),
            options.identity(), options.env());
    }
}
