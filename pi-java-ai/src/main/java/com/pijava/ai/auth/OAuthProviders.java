package com.pijava.ai.auth;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 已知 OAuth provider 配置注册表（pi {@code auth/oauth/} 全量）。
 *
 * <p>两类流程并存：{@link OAuthProvider.Pkce}（OpenRouter/Anthropic，浏览器回调 +
 * PKCE）与 {@link OAuthProvider.Device}（xAI/Kimi/GitHub Copilot/OpenAI Codex，
 * RFC 8628 设备码）。端点与 client-id 对齐 pi 各 provider 文件；Radius 按
 * gateway 工厂构造。</p>
 */
public final class OAuthProviders {

    private static final Map<String, OAuthProvider> ALL = Map.of(
        "openrouter", new OAuthProvider.Pkce(OAuthConfig.jsonStyle(
            "openrouter",
            "https://openrouter.ai/auth?callback_url={redirect_uri}"
                + "&code_challenge={code_challenge}&code_challenge_method=S256",
            "https://openrouter.ai/api/v1/auth/keys",
            Map.of("code_challenge_method", "S256"))),
        "anthropic", new OAuthProvider.Pkce(OAuthConfig.standard(
            "anthropic",
            "https://claude.ai/oauth/authorize",
            "https://platform.claude.com/v1/oauth/token",
            "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
            "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload")),
        "xai", new OAuthProvider.Device(DeviceCodeConfig.standard(
            "xai",
            "https://auth.x.ai/oauth2/device/code",
            "https://auth.x.ai/oauth2/token",
            "b1a00492-073a-47ea-816f-4c329264a828",
            "openid profile email offline_access grok-cli:access api:access")),
        "kimi", new OAuthProvider.Device(DeviceCodeConfig.standard(
            "kimi",
            "https://auth.kimi.com/api/oauth/device_authorization",
            "https://auth.kimi.com/api/oauth/token",
            "17e5f671-d194-4dfb-9706-5516cb48c098",
            "")),
        "github-copilot", new OAuthProvider.Device(DeviceCodeConfig.standard(
            "github-copilot",
            "https://github.com/login/device/code",
            "https://github.com/login/oauth/access_token",
            "Iv1.b507a08c87ecfe98",
            "read:user")),
        "openai-codex", new OAuthProvider.Device(DeviceCodeConfig.codex(
            "openai-codex",
            "https://auth.openai.com/api/accounts/deviceauth/usercode",
            "https://auth.openai.com/api/accounts/deviceauth/token",
            "app_EMoamEEZ73f0CkXaXp7hrann",
            "openid profile email offline_access",
            "https://auth.openai.com/codex/device",
            "https://auth.openai.com/oauth/token",
            "https://auth.openai.com/deviceauth/callback")));

    private OAuthProviders() {}

    /** 按 provider 名查 OAuth 配置；无则返回 empty。 */
    public static Optional<OAuthProvider> get(String provider) {
        return Optional.ofNullable(ALL.get(provider));
    }

    /** 已注册 provider 名。 */
    public static Set<String> names() {
        return ALL.keySet();
    }

    /** Radius 网关（pi {@code createRadiusOAuth}）：按 gateway 构造 device 配置。 */
    public static OAuthProvider radius(String name, String gateway) {
        String base = gateway.endsWith("/") ? gateway.substring(0, gateway.length() - 1) : gateway;
        return new OAuthProvider.Device(DeviceCodeConfig.standard(
            name,
            base + "/v1/oauth/device",
            base + "/v1/oauth/token",
            "pi-gateway",
            "gateway offline_access"));
    }
}
