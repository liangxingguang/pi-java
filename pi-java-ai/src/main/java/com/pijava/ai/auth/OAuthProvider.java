package com.pijava.ai.auth;

/**
 * 一个已注册 OAuth provider 的流程判别（pi {@code auth/oauth/load.ts}）。
 *
 * <p>sealed 二选一：{@link Pkce} 走 {@link OAuthFlow}（浏览器回调 PKCE），
 * {@link Device} 走 {@link DeviceCodeFlow}（RFC 8628 设备码）。</p>
 */
public sealed interface OAuthProvider permits OAuthProvider.Pkce, OAuthProvider.Device {

    /** 授权码 + PKCE 流程（OpenRouter、Anthropic）。 */
    record Pkce(OAuthConfig config) implements OAuthProvider {
    }

    /** RFC 8628 device-code 流程（xAI、Kimi、GitHub Copilot、OpenAI Codex、Radius）。 */
    record Device(DeviceCodeConfig config) implements OAuthProvider {
    }
}
