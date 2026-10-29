package com.pijava.ai.auth;

import java.io.IOException;

/**
 * 生产用 {@link OAuthTokenRefresher}：按 {@link OAuthProviders} 注册的流程形状分派
 * 通用 PKCE、专用浏览器、device-code 或 Radius 双流刷新。
 *
 * <p>这是原 {@code AuthCommand.refresh} 的下沉落点（包 B1 Step 1）。</p>
 */
public final class DefaultOAuthTokenRefresher implements OAuthTokenRefresher {

    @Override
    public OAuthCredential refresh(String provider, OAuthCredential credential) throws IOException {
        var spec = OAuthProviders.get(provider).orElseThrow(() ->
            new IOException("No OAuth flow registered for provider '" + provider + "'"));
        return switch (spec) {
            case OAuthProvider.Pkce(OAuthConfig c) -> new OAuthFlow(c).refresh(credential);
            case OAuthProvider.Anthropic ignored -> new AnthropicOAuthFlow().refresh(credential);
            case OAuthProvider.ChatGpt ignored -> new ChatGptOAuthFlow().refresh(credential);
            case OAuthProvider.Device(DeviceCodeConfig d) -> new DeviceCodeFlow(d).refresh(credential);
            case OAuthProvider.Radius(RadiusOAuthConfig c) -> new RadiusOAuthFlow(c).refresh(credential);
        };
    }
}
