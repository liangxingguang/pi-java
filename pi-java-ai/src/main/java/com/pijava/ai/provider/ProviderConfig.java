package com.pijava.ai.provider;

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
 * @param name               machine-readable id (e.g. {@code moonshotai-cn})
 * @param displayName        human-readable name
 * @param defaultBaseUrl     default API endpoint
 * @param apiKeyEnvVar       environment variable holding the API key;
 *                           {@code null} means the provider needs no key
 * @param defaultProtocol    protocol used when {@code ApiOptions.extra}
 *                           does not specify one
 * @param supportedProtocols all protocols this provider can serve
 * @param builtinModels      built-in model catalog
 */
public record ProviderConfig(
    String name,
    String displayName,
    String defaultBaseUrl,
    String apiKeyEnvVar,
    Protocol defaultProtocol,
    Set<Protocol> supportedProtocols,
    ModelCatalog builtinModels
) {
    /**
     * Validates required fields and copies the protocol set.
     *
     * <p>A blank {@code apiKeyEnvVar} is normalized to {@code null} so local
     * unauthenticated providers (Ollama) can omit a key.</p>
     */
    public ProviderConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(defaultBaseUrl, "defaultBaseUrl");
        Objects.requireNonNull(defaultProtocol, "defaultProtocol");
        if (apiKeyEnvVar != null && apiKeyEnvVar.isBlank()) {
            apiKeyEnvVar = null;
        }
        builtinModels = builtinModels == null ? ModelCatalog.empty() : builtinModels;
        supportedProtocols = supportedProtocols == null || supportedProtocols.isEmpty()
            ? Set.of(defaultProtocol)
            : Set.copyOf(supportedProtocols);
        if (!supportedProtocols.contains(defaultProtocol)) {
            throw new IllegalArgumentException(
                "defaultProtocol " + defaultProtocol + " not in supportedProtocols");
        }
    }

    /** Convenience constructor for single-protocol Providers. */
    public static ProviderConfig single(
            String name, String displayName, String baseUrl,
            String apiKeyEnvVar, Protocol protocol, ModelCatalog models) {
        return new ProviderConfig(
            name, displayName, baseUrl, apiKeyEnvVar, protocol, Set.of(protocol), models);
    }
}
