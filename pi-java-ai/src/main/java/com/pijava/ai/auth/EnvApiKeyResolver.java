package com.pijava.ai.auth;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves API keys from environment variables.
 *
 * <p>Maps provider names to their standard API-key environment
 * variable names, aligned with pi's {@code env-api-keys.ts}.</p>
 */
public final class EnvApiKeyResolver implements CredentialStore {

    private static final Map<String, String> ENV_VAR_MAP = Map.of(
            "anthropic", "ANTHROPIC_API_KEY",
            "openai",    "OPENAI_API_KEY",
            "google",    "GEMINI_API_KEY",
            "deepseek",  "DEEPSEEK_API_KEY",
            "mistral",   "MISTRAL_API_KEY");

    @Override
    public Optional<String> resolveApiKey(String provider) {
        var envVar = ENV_VAR_MAP.get(provider);
        if (envVar == null) {
            return Optional.empty();
        }
        var value = System.getenv(envVar);
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
}
