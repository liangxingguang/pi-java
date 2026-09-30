package com.pijava.ai.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.ai.api.AuthKind;

/**
 * 组合凭证解析：按激活 profile 优先，回落 stored OAuth，再回落默认凭证（P6-18）与
 * Anthropic 的两个 token 变量（包 A0 步7，{@code docs/43 D6}；包 B1，{@code docs/63}）。
 *
 * <p>解析顺序（从高到低）：</p>
 * <ol>
 *   <li>激活 profile 的环境变量（{@code <PROVIDER>_API_KEY_<PROFILE>}）⇒ {@code API_KEY}</li>
 *   <li>激活 profile 的文件凭证（{@code provider::profile}）⇒ {@code API_KEY}</li>
 *   <li><b>stored OAuth</b>（{@code OAuthCredentialStore}，包 B1）—— Anthropic 订阅 token
 *       ⇒ {@code API_KEY}（走 {@code x-api-key}，照 pi {@code toAuth⇒{apiKey}}）；
 *       其余 provider ⇒ {@code BEARER}（形态各自包再细分）</li>
 *   <li>{@code ANTHROPIC_AUTH_TOKEN} ⇒ {@code BEARER} —— <b>仅 Anthropic</b></li>
 *   <li>{@code ANTHROPIC_OAUTH_TOKEN} ⇒ {@code OAUTH} —— <b>仅 Anthropic</b></li>
 *   <li>默认环境变量（{@code <PROVIDER>_API_KEY}）⇒ {@code API_KEY}</li>
 *   <li>默认文件凭证 ⇒ {@code API_KEY}</li>
 * </ol>
 *
 * <p>第 3 层（stored OAuth）的插入位置照 pi 的裁决（{@code auth/resolve.ts:63-110}）：
 * stored credential 仅次 CLI/settings 直给、<b>高于 ambient env</b>（stored 拥有 provider），
 * 故排在两个 token 变量与默认 env 之前。第 4/5 层照 {@code providers/anthropic.ts:18-39}
 * （stored → {@code ANTHROPIC_AUTH_TOKEN} → {@code ANTHROPIC_OAUTH_TOKEN} →
 * {@code ANTHROPIC_API_KEY}）—— token 必须在 {@code ANTHROPIC_API_KEY} <b>之前</b>。
 * java 的 profile 两层是扩展，排在最前（显式激活的 profile 是比 stored 更强的信号）。</p>
 *
 * <p><b>有意不加</b>：profile 层不扩展出 {@code ..._AUTH_TOKEN_<PROFILE>}（pi 无此物）；
 * 默认文件 api_key 的既有 env-vs-store 次序不随 B1 改动（超出本包范围）。</p>
 */
public final class Credentials {

    /** stored OAuth 来源（读 store；临期则锁内单次刷新；失败抛 {@link OAuthRefreshException}）。 */
    @FunctionalInterface
    interface StoredOAuthSource {
        Optional<OAuthCredential> resolve(String provider);
    }

    /** 无 stored OAuth 来源（四参注入形态的占位）。 */
    private static final StoredOAuthSource NO_STORED_OAUTH = provider -> Optional.empty();

    private Credentials() {}

    /** 仅 Anthropic 的两个 token 变量及其形态（pi 只定义了这两个符号）。 */
    private static final Map<String, List<TokenEnv>> TOKEN_ENV_VARS = Map.of(
        "anthropic", List.of(
            new TokenEnv("ANTHROPIC_AUTH_TOKEN", AuthKind.BEARER),
            new TokenEnv("ANTHROPIC_OAUTH_TOKEN", AuthKind.OAUTH)));

    private record TokenEnv(String name, AuthKind kind) {}

    /** 解析 provider 当前生效的凭证（profile 感知，带形态；含 stored OAuth）。 */
    public static Optional<RecordedCredential> resolveCredential(String provider) {
        return resolveCredential(provider, new AuthProfileManager(),
            new EnvApiKeyResolver(), new FileCredentialStore(),
            new StoredOAuthCredentials()::resolveEffective);
    }

    /**
     * 解析 provider 当前生效的 API key（profile 感知）—— 保留给只要值的调用点
     * （如 {@code AuthCommand} 的展示命令）；需要形态的走
     * {@link #resolveCredential(String)}。
     */
    public static Optional<String> resolveApiKey(String provider) {
        return resolveCredential(provider).map(RecordedCredential::value);
    }

    /** 带注入 store 的解析（测试用）。 */
    static Optional<String> resolveApiKey(String provider, AuthProfileManager profiles,
                                          EnvApiKeyResolver env, FileCredentialStore file) {
        return resolveCredential(provider, profiles, env, file).map(RecordedCredential::value);
    }

    /** 带注入 store 的解析（测试用，不含 stored OAuth）—— 环境读取口经 {@link EnvApiKeyResolver} 注入。 */
    static Optional<RecordedCredential> resolveCredential(String provider,
                                                          AuthProfileManager profiles,
                                                          EnvApiKeyResolver env,
                                                          FileCredentialStore file) {
        return resolveCredential(provider, profiles, env, file, NO_STORED_OAUTH);
    }

    /** 五参形态：额外注入 stored OAuth 来源（新通道夹具用）。 */
    static Optional<RecordedCredential> resolveCredential(String provider,
                                                          AuthProfileManager profiles,
                                                          EnvApiKeyResolver env,
                                                          FileCredentialStore file,
                                                          StoredOAuthSource storedOAuth) {
        var profile = profiles.activeProfile(provider);
        if (profile.isPresent()) {
            var envValue = env.resolveApiKey(provider, profile.get());
            if (envValue.isPresent()) {
                return Optional.of(plain(envValue.get(),
                    "env:" + providerEnvVar(provider) + "_" + profile.get().toUpperCase()));
            }
            var fileValue = file.resolveApiKey(provider, profile.get());
            if (fileValue.isPresent()) {
                return Optional.of(plain(fileValue.get(), "stored:" + provider + "::" + profile.get()));
            }
        }
        var storedOauthCredential = storedOAuth.resolve(provider);
        if (storedOauthCredential.isPresent()) {
            return Optional.of(new RecordedCredential(oauthKind(provider),
                storedOauthCredential.get().accessToken(), "stored-oauth:" + provider));
        }
        for (var token : TOKEN_ENV_VARS.getOrDefault(provider, List.of())) {
            var value = env.resolveEnv(token.name());
            if (value.isPresent()) {
                return Optional.of(new RecordedCredential(token.kind(), value.get(), "env:" + token.name()));
            }
        }
        var envValue = env.resolveApiKey(provider);
        if (envValue.isPresent()) {
            return Optional.of(plain(envValue.get(), "env:" + providerEnvVar(provider)));
        }
        return file.resolveApiKey(provider)
            .map(value -> plain(value, "stored:" + provider));
    }

    private static RecordedCredential plain(String value, String source) {
        return new RecordedCredential(AuthKind.API_KEY, value, source);
    }

    /**
     * stored OAuth 的形态：Anthropic 订阅 access token 照 pi {@code toAuth⇒{apiKey}}
     * 走 {@code x-api-key}（{@code API_KEY}），不是 Bearer；其余 provider 的 OAuth access
     * token 暂记 {@code BEARER}（各自 toAuth 形态随对应包再对齐）。
     */
    private static AuthKind oauthKind(String provider) {
        return "anthropic".equals(provider) ? AuthKind.API_KEY : AuthKind.BEARER;
    }

    /** 该 provider 的 API key 环境变量名（仅用于 source 文案，取不到时回落 provider 名）。 */
    private static String providerEnvVar(String provider) {
        var name = ENV_NAMES.envVarName(provider);
        return name == null ? provider : name;
    }

    /** 只读的环境变量名表（{@link EnvApiKeyResolver} 是只读的，复用一份即可）。 */
    private static final EnvApiKeyResolver ENV_NAMES = new EnvApiKeyResolver();
}