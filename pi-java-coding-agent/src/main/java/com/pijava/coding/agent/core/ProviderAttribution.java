package com.pijava.coding.agent.core;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.catalog.ModelInfo;

/**
 * 批 20（B253）：pi {@code provider-attribution.ts} 全件对位（:1-97）——
 * attribution 头层。四家头集与合并序逐字：
 *
 * <ul>
 *   <li>openrouter（provider 名或 baseUrl <b>含</b> {@code openrouter.ai}）⇒
 *       {@code HTTP-Referer}/{@code X-OpenRouter-Title}/{@code X-OpenRouter-Categories}；</li>
 *   <li>nvidia（provider 名或 host 等值）⇒ {@code X-BILLING-INVOKE-ORIGIN: Pi}；</li>
 *   <li>cloudflare（两 provider 名或两 host）⇒ {@code User-Agent: pi-coding-agent}；</li>
 *   <li>opencode（两 provider 名或 host，<b>不过遥测门</b>）⇒
 *       {@code x-opencode-session}/{@code x-opencode-client: pi}。</li>
 * </ul>
 *
 * <p>合并序（pi :85-94 逐字）：session 头 → attribution 头 → <b>请求头最后（赢）</b>；
 * 空集 ⇒ 不造 {@code extra["headers"]} 键（pi 返回 {@code undefined} 对位）。
 * 头值逐字含品牌串（{@code "pi"}/{@code "https://pi.dev"}）——线格 parity 与
 * ai 批 6 身份伪装同例。</p>
 */
public final class ProviderAttribution {

    private static final String OPENROUTER_HOST = "openrouter.ai";
    private static final String NVIDIA_NIM_HOST = "integrate.api.nvidia.com";
    private static final String CLOUDFLARE_API_HOST = "api.cloudflare.com";
    private static final String CLOUDFLARE_AI_GATEWAY_HOST = "gateway.ai.cloudflare.com";
    private static final String OPENCODE_HOST = "opencode.ai";

    private ProviderAttribution() {}

    /**
     * pi {@code mergeProviderAttributionHeaders}（:79-97）的 java 落点：把合并结果
     * 写进 {@code extra["headers"]}（{@link ApiOptions} 六参重建，authKind 保真——
     * B138 同族教训）。
     *
     * @param options                 当前请求选项（{@code extra["sessionId"]} 与
     *                                {@code extra["headers"]} 可缺席）
     * @param model                   生效目录解析的模型（provider 名判定的操作数）
     * @param providerDefaultBaseUrl  provider 静态默认 baseUrl（裁决⑥：pi
     *                                {@code model.baseUrl} 是目录值——不取 CLI/settings
     *                                覆盖；{@code null} ⇒ host 判定全假，provider
     *                                名臂仍活）
     * @param telemetryEnabled        遥测门终值（{@link InstallTelemetry}）
     */
    public static ApiOptions apply(ApiOptions options, ModelInfo model,
                                   String providerDefaultBaseUrl,
                                   boolean telemetryEnabled) {
        String baseUrl = model.baseUrl() != null ? model.baseUrl()
            : (providerDefaultBaseUrl == null ? "" : providerDefaultBaseUrl);
        String provider = model.id().provider();
        var merged = new LinkedHashMap<String, String>();
        merged.putAll(sessionHeaders(provider, baseUrl, sessionIdOf(options.extra())));
        if (telemetryEnabled) {
            merged.putAll(defaultAttributionHeaders(provider, baseUrl));
        }
        // pi :90-94：请求头最后并入——压 attribution（models.json 三层/auth 显式赢）。
        merged.putAll(headersOf(options.extra()));
        if (merged.isEmpty()) {
            return options;
        }
        var extra = new LinkedHashMap<String, Object>(options.extra());
        extra.put("headers", merged);
        return new ApiOptions(options.baseUrl(), options.apiKey(), options.timeout(),
            options.maxRetries(), extra, options.authKind());
    }

    /** pi {@code getSessionHeaders}（:67-77 逐字）——<b>不过遥测门</b>。 */
    private static Map<String, String> sessionHeaders(String provider, String baseUrl,
                                                      String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Map.of();
        }
        if (!"opencode".equals(provider) && !"opencode-go".equals(provider)
                && !matchesHost(baseUrl, OPENCODE_HOST)) {
            return Map.of();
        }
        // pi 对象字面量键序（线格序）——LinkedHashMap 保序。
        var headers = new LinkedHashMap<String, String>();
        headers.put("x-opencode-session", sessionId);
        headers.put("x-opencode-client", "pi");
        return headers;
    }

    /** pi {@code getDefaultAttributionHeaders}（:36-65 逐字；门已在调用方）。 */
    private static Map<String, String> defaultAttributionHeaders(String provider,
                                                                 String baseUrl) {
        if (isOpenRouterModel(provider, baseUrl)) {
            // pi 字面量键序（:45-49）。
            var headers = new LinkedHashMap<String, String>();
            headers.put("HTTP-Referer", "https://pi.dev");
            headers.put("X-OpenRouter-Title", "pi");
            headers.put("X-OpenRouter-Categories", "cli-agent");
            return headers;
        }
        if (isNvidiaNimModel(provider, baseUrl)) {
            return Map.of("X-BILLING-INVOKE-ORIGIN", "Pi");
        }
        if (isCloudflareModel(provider, baseUrl)) {
            return Map.of("User-Agent", "pi-coding-agent");
        }
        return Map.of();
    }

    /** pi {@code isOpenRouterModel}（:19-21 逐字——contains，非 host 等值）。 */
    private static boolean isOpenRouterModel(String provider, String baseUrl) {
        return "openrouter".equals(provider) || baseUrl.contains(OPENROUTER_HOST);
    }

    /** pi {@code isNvidiaNimModel}（:23-25 逐字）。 */
    private static boolean isNvidiaNimModel(String provider, String baseUrl) {
        return "nvidia".equals(provider) || matchesHost(baseUrl, NVIDIA_NIM_HOST);
    }

    /** pi {@code isCloudflareModel}（:27-34 逐字）。 */
    private static boolean isCloudflareModel(String provider, String baseUrl) {
        return "cloudflare-workers-ai".equals(provider)
            || "cloudflare-ai-gateway".equals(provider)
            || matchesHost(baseUrl, CLOUDFLARE_API_HOST)
            || matchesHost(baseUrl, CLOUDFLARE_AI_GATEWAY_HOST);
    }

    /** pi {@code matchesHost}（:11-17 逐字）：URL 解析失败的 host 等值 ⇒ false。 */
    private static boolean matchesHost(String baseUrl, String expectedHost) {
        try {
            return expectedHost.equals(java.net.URI.create(baseUrl).getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String sessionIdOf(Map<String, Object> extra) {
        Object raw = extra == null ? null : extra.get("sessionId");
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static Map<String, String> headersOf(Map<String, Object> extra) {
        Object raw = extra == null ? null : extra.get("headers");
        if (raw instanceof Map<?, ?> map) {
            var headers = new LinkedHashMap<String, String>();
            map.forEach((k, v) -> headers.put(
                String.valueOf(k), v == null ? null : v.toString()));
            return headers;
        }
        return Map.of();
    }
}
