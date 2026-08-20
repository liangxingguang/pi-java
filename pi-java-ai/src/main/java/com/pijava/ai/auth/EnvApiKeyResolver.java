package com.pijava.ai.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.builtin.ProviderCatalog;

/**
 * Resolves API keys from environment variables.
 *
 * <p>Maps provider names to their API-key environment variable names,
 * derived from {@link ProviderCatalog} so new providers stay in sync.</p>
 */
public final class EnvApiKeyResolver implements CredentialStore {

    private static final Map<String, String> ENV_VAR_MAP = buildEnvVarMap();

    @Override
    public Optional<String> resolveApiKey(String provider) {
        var envVar = ENV_VAR_MAP.get(provider);
        if (envVar == null || envVar.isBlank()) {
            return Optional.empty();
        }
        var value = System.getenv(envVar);
        return Optional.ofNullable(value).filter(v -> !v.isBlank());
    }

    /**
     * 按 profile 解析：环境变量 {@code <PROVIDER>_API_KEY_<PROFILE>}（P6-18）。
     * 如 {@code ANTHROPIC_API_KEY_WORK}。
     */
    public Optional<String> resolveApiKey(String provider, String profile) {
        var envVar = ENV_VAR_MAP.get(provider);
        if (envVar == null || envVar.isBlank()) {
            return Optional.empty();
        }
        var value = System.getenv(envVar + "_" + profile.toUpperCase());
        return Optional.ofNullable(value).filter(v -> !v.isBlank());
    }

    @Override
    public void storeApiKey(String provider, String apiKey) {
        throw new UnsupportedOperationException(
                "EnvApiKeyResolver is read-only. Use FileCredentialStore to persist keys.");
    }

    @Override
    public void deleteApiKey(String provider) {
        throw new UnsupportedOperationException(
                "EnvApiKeyResolver is read-only.");
    }

    private static Map<String, String> buildEnvVarMap() {
        var map = new LinkedHashMap<String, String>();
        for (var provider : ProviderCatalog.all()) {
            if (provider instanceof ConfigurableProvider configurable) {
                var envVar = configurable.providerConfig().apiKeyEnvVar();
                if (envVar != null && !envVar.isBlank()) {
                    map.put(provider.name(), envVar);
                }
            }
        }
        return Map.copyOf(map);
    }
}
