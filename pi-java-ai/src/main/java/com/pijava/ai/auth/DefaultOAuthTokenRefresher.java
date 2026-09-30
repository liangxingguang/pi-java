package com.pijava.ai.auth;

import java.io.IOException;

/**
 * 生产用 {@link OAuthTokenRefresher}：按 {@link OAuthProviders} 注册的流程形状分派
 * PKCE（{@link OAuthFlow}）或 device-code（{@link DeviceCodeFlow}）刷新。
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
            case OAuthProvider.Device(DeviceCodeConfig d) -> new DeviceCodeFlow(d).refresh(credential);
        };
    }
}
