package com.pijava.ai.auth;

/**
 * 一个已注册 OAuth provider 的流程判别（pi {@code auth/oauth/load.ts}）。
 *
 * <p>sealed 三选一：{@link Pkce} 走通用 {@link OAuthFlow}（openrouter 形状）、
 * {@link Anthropic} 走专用 {@link AnthropicOAuthFlow}（browser/copy-code 两登录法、
 * JSON token 体、固定端口回落），{@link Device} 走 {@link DeviceCodeFlow}
 * （RFC 8628 设备码）。</p>
 */
public sealed interface OAuthProvider
        permits OAuthProvider.Pkce, OAuthProvider.Anthropic, OAuthProvider.Device {

    /** 通用授权码 + PKCE 流程（OpenRouter）。 */
    record Pkce(OAuthConfig config) implements OAuthProvider {
    }

    /** Anthropic Claude Pro/Max 订阅（浏览器回调 / copy-code 两法）。 */
    record Anthropic() implements OAuthProvider {
    }

    /** RFC 8628 device-code 流程（xAI、Kimi、GitHub Copilot、OpenAI Codex、Radius）。 */
    record Device(DeviceCodeConfig config) implements OAuthProvider {
    }
}
