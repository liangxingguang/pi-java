package com.pijava.ai.identity;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identity header computation.
 *
 * <p>逐字对位 pi {@code packages/ai/src/utils/identity.ts:61-145}：
 * {@code formatUserAgent}、{@code getAttributionHeaders}、
 * {@code getOpenCodeSessionHeaders}、{@code getIdentityHeaders}。</p>
 *
 * <p>内容边界：只读 identity 与 target，不接触消息正文、工具参数或凭证。</p>
 */
public final class IdentityHeaders {

    /** Internal {@code ApiOptions.extra} key holding the pre-computed header map. */
    public static final String EXTRA_KEY = "identityHeaders";

    private static final String OPENROUTER_TOKEN = "openrouter.ai";
    private static final String NVIDIA_NIM_HOST = "integrate.api.nvidia.com";
    private static final String OPENCODE_HOST = "opencode.ai";

    private IdentityHeaders() {}

    /** The detailed default UA with the current JVM runtime. */
    public static String formatUserAgent(ClientIdentity identity) {
        return formatUserAgent(identity, RuntimeInfo.current());
    }

    /** pi {@code formatUserAgent}（{@code :75-88}）——运行时信息可注入。 */
    public static String formatUserAgent(ClientIdentity identity, RuntimeInfo runtime) {
        UserAgentMode mode = identity == null || identity.userAgent() == null
            ? UserAgentMode.DETAILED : identity.userAgent();
        if (mode instanceof UserAgentMode.Custom custom) {
            return custom.value();
        }
        if (mode instanceof UserAgentMode.Short) {
            return "pi";
        }
        String version = identity != null && identity.version() != null
            ? identity.version() : PiAiVersion.VERSION;
        return "pi-java/" + version + " (" + runtime.platform() + "; "
            + runtime.runtime() + "; " + runtime.arch() + ")";
    }

    /** pi {@code getAttributionHeaders}（{@code :107-125}）——空表表示无归因。 */
    public static Map<String, String> attributionHeaders(
            ClientIdentityTarget target, ClientIdentity identity) {
        if (identity == null
                || !(identity.attribution() instanceof AttributionSetting.Value value)) {
            return Map.of();
        }
        var attribution = value.attribution();
        if (isOpenRouter(target)) {
            var headers = new LinkedHashMap<String, String>();
            headers.put("HTTP-Referer", attribution.url());
            headers.put("X-OpenRouter-Title", attribution.name());
            var categories = attribution.categories();
            if (categories != null && !categories.isEmpty()) {
                headers.put("X-OpenRouter-Categories", String.join(",", categories));
            }
            return headers;
        }
        if (isNvidiaNim(target)) {
            return Map.of("X-BILLING-INVOKE-ORIGIN", capitalize(attribution.name()));
        }
        if (isOpenCode(target)) {
            return Map.of("x-opencode-client", attribution.name());
        }
        return Map.of();
    }

    /** pi {@code getOpenCodeSessionHeaders}（{@code :130-133}）。 */
    public static Map<String, String> openCodeSessionHeaders(
            ClientIdentityTarget target, String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || !isOpenCode(target)) {
            return Map.of();
        }
        return Map.of("x-opencode-session", sessionId);
    }

    /** pi {@code getIdentityHeaders}（{@code :141-143}）：UA 在前，归因头随后。 */
    public static Map<String, String> identityHeaders(
            ClientIdentityTarget target, ClientIdentity identity) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", formatUserAgent(identity));
        headers.putAll(attributionHeaders(target, identity));
        return headers;
    }

    /**
     * Merge header maps with pi {@code Headers.set/delete} semantics: case-insensitive
     * replacement, {@code null} value removes the same-name lower-layer key.
     */
    public static Map<String, String> mergeHeaders(
            Map<String, String> base, Map<?, ?> override) {
        var merged = new LinkedHashMap<String, String>(base == null ? Map.of() : base);
        if (override == null) {
            return merged;
        }
        override.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String name = key.toString();
            removeCaseInsensitive(merged, name);
            if (value != null) {
                merged.put(name, value.toString());
            }
        });
        return merged;
    }

    private static boolean isOpenRouter(ClientIdentityTarget target) {
        return "openrouter".equals(target.provider())
            || target.baseUrl().contains(OPENROUTER_TOKEN);
    }

    private static boolean isNvidiaNim(ClientIdentityTarget target) {
        return "nvidia".equals(target.provider())
            || matchesHost(target.baseUrl(), NVIDIA_NIM_HOST);
    }

    private static boolean isOpenCode(ClientIdentityTarget target) {
        return "opencode".equals(target.provider())
            || "opencode-go".equals(target.provider())
            || matchesHost(target.baseUrl(), OPENCODE_HOST);
    }

    private static boolean matchesHost(String baseUrl, String expectedHost) {
        try {
            return expectedHost.equals(URI.create(baseUrl).getHost());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static void removeCaseInsensitive(Map<String, String> headers, String name) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
    }
}
