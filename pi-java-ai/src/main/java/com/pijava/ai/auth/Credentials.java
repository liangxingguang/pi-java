package com.pijava.ai.auth;

import java.util.Optional;

/**
 * 组合凭证解析：按激活 profile 优先，回落默认凭证（P6-18）。
 *
 * <p>解析顺序：① 激活 profile 的环境变量（{@code <PROVIDER>_API_KEY_<PROFILE>}）；
 * ② 激活 profile 的文件凭证（{@code provider::profile}）；③ 默认环境变量；
 * ④ 默认文件凭证。</p>
 */
public final class Credentials {

    private Credentials() {}

    /** 解析 provider 当前生效的 API key（profile 感知）。 */
    public static Optional<String> resolveApiKey(String provider) {
        return resolveApiKey(provider, new AuthProfileManager(),
            new EnvApiKeyResolver(), new FileCredentialStore());
    }

    /** 带注入 store 的解析（测试用）。 */
    static Optional<String> resolveApiKey(String provider, AuthProfileManager profiles,
                                          EnvApiKeyResolver env, FileCredentialStore file) {
        var profile = profiles.activeProfile(provider);
        if (profile.isPresent()) {
            var envValue = env.resolveApiKey(provider, profile.get());
            if (envValue.isPresent()) {
                return envValue;
            }
            var fileValue = file.resolveApiKey(provider, profile.get());
            if (fileValue.isPresent()) {
                return fileValue;
            }
        }
        var envValue = env.resolveApiKey(provider);
        if (envValue.isPresent()) {
            return envValue;
        }
        return file.resolveApiKey(provider);
    }
}
