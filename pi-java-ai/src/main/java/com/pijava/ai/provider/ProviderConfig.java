package com.pijava.ai.provider;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.pijava.ai.catalog.ModelCatalog;

/**
 * Static configuration for a Provider — one immutable config per Provider.
 *
 * <p>Supports multi-protocol Providers (pi's fireworks supports both
 * anthropic-messages and openai-completions, opencode supports 4).
 * Single-protocol Providers can use {@link #single} for convenience.</p>
 *
 * <p>docs/11 A1：{@code apiKeyEnvVars} 是<b>回落链</b>（pi {@code envApiKeyAuth}
 * 接受 {@code string | readonly string[]}）——Qwen Token Plan 的四个 provider 各有
 * 「专属键 → 区域共享键」两层；空链 ≙ 旧版的 {@code null}（本地无鉴权 provider）。</p>
 *
 * @param name               machine-readable id (e.g. {@code moonshotai-cn})
 * @param displayName        human-readable name
 * @param defaultBaseUrl     default API endpoint
 * @param apiKeyEnvVars      environment variables holding the API key, in
 *                           fallback order; empty means the provider needs no key
 * @param defaultProtocol    protocol used when {@code ApiOptions.extra}
 *                           does not specify one
 * @param supportedProtocols all protocols this provider can serve
 * @param builtinModels      built-in model catalog
 */
public record ProviderConfig(
    String name,
    String displayName,
    String defaultBaseUrl,
    List<String> apiKeyEnvVars,
    Protocol defaultProtocol,
    Set<Protocol> supportedProtocols,
    ModelCatalog builtinModels
) {
    /**
     * Validates required fields and normalizes the collections.
     *
     * <p>Blank entries are dropped so a chain never carries an unusable name;
     * an empty chain replaces the old {@code null} for local unauthenticated
     * providers (Ollama).</p>
     */
    public ProviderConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(defaultBaseUrl, "defaultBaseUrl");
        Objects.requireNonNull(defaultProtocol, "defaultProtocol");
        apiKeyEnvVars = apiKeyEnvVars == null ? List.of()
            : apiKeyEnvVars.stream().filter(v -> v != null && !v.isBlank()).toList();
        builtinModels = builtinModels == null ? ModelCatalog.empty() : builtinModels;
        supportedProtocols = supportedProtocols == null || supportedProtocols.isEmpty()
            ? Set.of(defaultProtocol)
            : Set.copyOf(supportedProtocols);
        if (!supportedProtocols.contains(defaultProtocol)) {
            throw new IllegalArgumentException(
                "defaultProtocol " + defaultProtocol + " not in supportedProtocols");
        }
    }

    /**
     * The primary environment variable name — first entry of the chain, or
     * {@code null} when the provider needs no key. Diagnostics and profile
     * extensions only look at this one (pi {@code envVarName} 同口径).
     */
    public String apiKeyEnvVar() {
        return apiKeyEnvVars.isEmpty() ? null : apiKeyEnvVars.getFirst();
    }

    /** Convenience constructor for single-protocol Providers with one env var. */
    public static ProviderConfig single(
            String name, String displayName, String baseUrl,
            String apiKeyEnvVar, Protocol protocol, ModelCatalog models) {
        return single(name, displayName, baseUrl,
            apiKeyEnvVar == null ? List.of() : List.of(apiKeyEnvVar), protocol, models);
    }

    /** Convenience constructor for single-protocol Providers with an env chain. */
    public static ProviderConfig single(
            String name, String displayName, String baseUrl,
            List<String> apiKeyEnvVars, Protocol protocol, ModelCatalog models) {
        return new ProviderConfig(
            name, displayName, baseUrl, apiKeyEnvVars, protocol, Set.of(protocol), models);
    }
}
