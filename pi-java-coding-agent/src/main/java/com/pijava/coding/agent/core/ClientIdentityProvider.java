package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.ai.identity.AttributionSetting;
import com.pijava.ai.identity.ClientAttribution;
import com.pijava.ai.identity.ClientIdentity;
import com.pijava.ai.identity.ClientIdentityTarget;
import com.pijava.ai.identity.IdentityHeaders;
import com.pijava.ai.identity.UserAgentMode;
import com.pijava.coding.agent.cli.Version;

/**
 * docs/14 B266：pi {@code client-identity.ts}（{@code packages/coding-agent/src/core/client-identity.ts:7-14}）
 * 的 Java 落点。
 *
 * <p>pi 的请求身份是 detailed {@code User-Agent}＋版本；归因头在安装遥测开启时
 * 指向 {@code PI_ATTRIBUTION}，关闭时显式 {@code null}（三态里的 {@code Off}）。</p>
 */
public final class ClientIdentityProvider {

    private static final ClientAttribution PI_ATTRIBUTION = new ClientAttribution(
        "pi", "https://pi.dev", List.of("cli-agent"));

    private ClientIdentityProvider() {}

    /** pi {@code getClientIdentity(settingsManager)} 的逐请求求值。 */
    public static ClientIdentity get(Settings settings) {
        return get(settings, System.getenv("PI_TELEMETRY"));
    }

    /** 第二参全形（测试缝——pi 的默认参注入同 {@link InstallTelemetry#isEnabled(Boolean, String)}）。 */
    static ClientIdentity get(Settings settings, String telemetryEnv) {
        return new ClientIdentity(
            UserAgentMode.DETAILED,
            InstallTelemetry.isEnabled(
                settings == null ? null : settings.enableInstallTelemetry, telemetryEnv)
                ? AttributionSetting.of(PI_ATTRIBUTION)
                : AttributionSetting.OFF,
            Version.VERSION);
    }

    /**
     * pi {@code getPiUserAgent(VERSION)}（{@code utils/pi-user-agent.ts}）——
     * coding-agent 自有网络请求的 UA。
     */
    public static String userAgent() {
        return IdentityHeaders.formatUserAgent(
            new ClientIdentity(UserAgentMode.DETAILED, AttributionSetting.UNSET, Version.VERSION));
    }

    /**
     * pi {@code getOpenCodeSessionHeaders} 在宿主请求组装点的应用：先 session、后既有头
     * （既有头赢）。空结果 ⇒ 原样返回。
     */
    static com.pijava.ai.api.ApiOptions withOpenCodeSessionHeaders(
            com.pijava.ai.api.ApiOptions options,
            com.pijava.ai.catalog.ModelInfo model,
            String providerDefaultBaseUrl,
            String sessionId) {
        String provider = model == null ? "" : model.id().provider();
        String baseUrl = model != null && model.baseUrl() != null
            ? model.baseUrl() : (providerDefaultBaseUrl == null ? "" : providerDefaultBaseUrl);
        var session = IdentityHeaders.openCodeSessionHeaders(
            new ClientIdentityTarget(provider, baseUrl), sessionId);
        if (session.isEmpty()) {
            return options;
        }
        var existing = headersOf(options.extra());
        var extra = new java.util.LinkedHashMap<String, Object>(options.extra());
        extra.put("headers", IdentityHeaders.mergeHeaders(session, existing));
        return new com.pijava.ai.api.ApiOptions(options.baseUrl(), options.apiKey(),
            options.timeout(), options.maxRetries(), extra, options.authKind(),
            options.telemetry(), options.identity());
    }

    private static java.util.Map<String, String> headersOf(java.util.Map<String, Object> extra) {
        Object raw = extra == null ? null : extra.get("headers");
        var headers = new java.util.LinkedHashMap<String, String>();
        if (raw instanceof java.util.Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    headers.put(key.toString(), value.toString());
                }
            });
        }
        return headers;
    }
}
