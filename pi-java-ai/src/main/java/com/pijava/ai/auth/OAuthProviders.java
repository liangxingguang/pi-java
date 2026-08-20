package com.pijava.ai.auth;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 已知 OAuth provider 配置注册表（P6-17）。
 *
 * <p>当前仅含 OpenRouter（OAuth 换长期 API key，token 请求走 JSON 编码，
 * 对齐 pi 的 {@code openrouter.ts}）。其余 pi OAuth provider（anthropic 订阅、
 * github-copilot、kimi-coding、xai、radius 等）端点各不相同，属平台定制，
 * 待各自接入时按 {@link OAuthConfig} 补充。</p>
 */
public final class OAuthProviders {

    private static final Map<String, OAuthConfig> ALL = Map.of(
        "openrouter", OAuthConfig.jsonStyle(
            "openrouter",
            "https://openrouter.ai/auth?callback_url={redirect_uri}"
                + "&code_challenge={code_challenge}&code_challenge_method=S256",
            "https://openrouter.ai/api/v1/auth/keys",
            Map.of("code_challenge_method", "S256")));

    private OAuthProviders() {}

    /** 按 provider 名查 OAuth 配置；无则返回 empty。 */
    public static Optional<OAuthConfig> get(String provider) {
        return Optional.ofNullable(ALL.get(provider));
    }

    /** 已注册 provider 名。 */
    public static Set<String> names() {
        return ALL.keySet();
    }
}
