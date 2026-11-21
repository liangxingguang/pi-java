package com.pijava.ai.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.builtin.ProviderCatalog;

/**
 * Resolves API keys from environment variables.
 *
 * <p>Maps provider names to their API-key environment variable <b>chains</b>
 * (docs/11 A2；pi {@code envApiKeyAuth} 接受单值或数组，按序取第一个非空),
 * derived from {@link ProviderCatalog} so new providers stay in sync.</p>
 */
public final class EnvApiKeyResolver implements CredentialStore {

    /** 命中的变量名与值（{@code source} 文案要报实际命中的那个键）。 */
    public record EnvMatch(String name, String value) {}

    private static final Map<String, List<String>> ENV_VAR_MAP = buildEnvVarMap();

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
     * 该 provider 的<b>主</b> API key 环境变量名（链首；未登记则 {@code null}）——
     * 供诊断文案使用（包 A0：凭证出处 {@code env:<VAR>}）。
     */
    public String envVarName(String provider) {
        var chain = ENV_VAR_MAP.get(provider);
        return chain == null || chain.isEmpty() ? null : chain.getFirst();
    }

    /**
     * 读**任意**环境变量名 —— 包 A0 步7：两个 Anthropic token 变量不在 provider 映射表里
     * （pi 也只把它们定义为两个独立符号），但仍要走同一个注入的读取口。
     */
    Optional<String> resolveEnv(String name) {
        return Optional.ofNullable(envLookup.apply(name)).filter(v -> !v.isBlank());
    }

    /**
     * 按链取第一个非空的环境变量（docs/11 A2）；返回值携带实际命中的变量名。
     */
    public Optional<EnvMatch> resolveApiKeyMatch(String provider) {
        var chain = ENV_VAR_MAP.get(provider);
        if (chain == null || chain.isEmpty()) {
            return Optional.empty();
        }
        for (var name : chain) {
            var value = resolveEnv(name);
            if (value.isPresent()) {
                return Optional.of(new EnvMatch(name, value.get()));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> resolveApiKey(String provider) {
        return resolveApiKeyMatch(provider).map(EnvMatch::value);
    }

    /**
     * 按 profile 解析：环境变量 {@code <PROVIDER>_API_KEY_<PROFILE>}（P6-18）。
     * 如 {@code ANTHROPIC_API_KEY_WORK}。<b>只看链首</b>（profile 扩展不是 pi 行为）。
     */
    public Optional<String> resolveApiKey(String provider, String profile) {
        var envVar = envVarName(provider);
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

    private static Map<String, List<String>> buildEnvVarMap() {
        var map = new LinkedHashMap<String, List<String>>();
        for (var provider : ProviderCatalog.all()) {
            if (provider instanceof ConfigurableProvider configurable) {
                var chain = configurable.providerConfig().apiKeyEnvVars();
                if (!chain.isEmpty()) {
                    map.put(provider.name(), chain);
                }
            }
        }
        return Map.copyOf(map);
    }
}
