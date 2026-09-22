package com.pijava.ai.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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

    private final Function<String, String> envLookup;

    /** 读进程环境的解析器（生产用）。 */
    public EnvApiKeyResolver() {
        this(System::getenv);
    }

    /**
     * 注入环境读取的解析器（测试用；包 A0 起凭证链的次序断言需要在可控环境里跑）。
     */
    EnvApiKeyResolver(Function<String, String> envLookup) {
        this.envLookup = envLookup;
    }

    /**
     * 该 provider 的 API key 环境变量名（未登记则 {@code null}）—— 供诊断文案使用
     * （包 A0：凭证出处 {@code env:<VAR>}）。
     */
    public String envVarName(String provider) {
        return ENV_VAR_MAP.get(provider);
    }

    /**
     * 读**任意**环境变量名 —— 包 A0 步7：两个 Anthropic token 变量不在 provider 映射表里
     * （pi 也只把它们定义为两个独立符号），但仍要走同一个注入的读取口。
     */
    Optional<String> resolveEnv(String name) {
        return Optional.ofNullable(envLookup.apply(name)).filter(v -> !v.isBlank());
    }

    @Override
    public Optional<String> resolveApiKey(String provider) {
        var envVar = ENV_VAR_MAP.get(provider);
        if (envVar == null || envVar.isBlank()) {
            return Optional.empty();
        }
        return resolveEnv(envVar);
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
        return resolveEnv(envVar + "_" + profile.toUpperCase());
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
