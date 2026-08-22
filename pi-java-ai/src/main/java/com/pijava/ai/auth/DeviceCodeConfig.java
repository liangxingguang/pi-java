package com.pijava.ai.auth;

/**
 * OAuth2 RFC 8628 device-code flow 的 provider 配置（pi {@code auth/oauth/*.ts}）。
 *
 * <p>device-code 流程：客户端向 {@code deviceCodeUrl} 请求设备授权，向用户展示
 * verification URI + user code，然后轮询 {@code tokenUrl} 直到用户完成授权。
 * 多数 provider（xAI/Kimi/GitHub Copilot/Radius）直接返回 access token；
 * OpenAI Codex 的 device 轮询返回 {@code authorization_code + code_verifier}，
 * 需再走一次 PKCE 授权码交换（{@link DeviceAuthStyle#OPENAI_CODEX}）。</p>
 */
public record DeviceCodeConfig(
    String name,
    String deviceCodeUrl,
    String tokenUrl,
    String clientId,
    String scope,
    String verificationUri,
    DeviceAuthStyle deviceStyle,
    String exchangeUrl,
    String exchangeRedirectUri
) {

    /** 设备授权的两种轮询形状。 */
    public enum DeviceAuthStyle {
        /** 标准 RFC 8628：token 轮询体为 {@code grant_type=device_code + client_id + device_code}。 */
        STANDARD,
        /** OpenAI Codex：device 轮询体为 JSON {@code device_auth_id + user_code}，成功后 PKCE 交换。 */
        OPENAI_CODEX
    }

    /** 标准 RFC 8628 device-code 配置（verification URI 取自设备响应）。 */
    public static DeviceCodeConfig standard(String name, String deviceCodeUrl,
                                            String tokenUrl, String clientId, String scope) {
        return new DeviceCodeConfig(name, deviceCodeUrl, tokenUrl, clientId, scope,
            "", DeviceAuthStyle.STANDARD, "", "");
    }

    /** OpenAI Codex 两段式配置：device 轮询 + PKCE 授权码交换。 */
    public static DeviceCodeConfig codex(String name, String deviceCodeUrl, String tokenUrl,
                                         String clientId, String scope, String verificationUri,
                                         String exchangeUrl, String exchangeRedirectUri) {
        return new DeviceCodeConfig(name, deviceCodeUrl, tokenUrl, clientId, scope,
            verificationUri, DeviceAuthStyle.OPENAI_CODEX, exchangeUrl, exchangeRedirectUri);
    }
}
