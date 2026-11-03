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
    String exchangeRedirectUri,
    UriPolicy uriPolicy,
    CompleteUriMode completeUriMode
) {

    /** 设备授权的两种轮询形状。 */
    public enum DeviceAuthStyle {
        /** 标准 RFC 8628：token 轮询体为 {@code grant_type=device_code + client_id + device_code}。 */
        STANDARD,
        /** OpenAI Codex：device 轮询体为 JSON {@code device_auth_id + user_code}，成功后 PKCE 交换。 */
        OPENAI_CODEX
    }

    /** docs/19 #43：服务端 verification URI 的协议策略。 */
    public enum UriPolicy {
        /** 接受 {@code http:} 与 {@code https:}（GitHub Copilot、Kimi、Radius）。 */
        HTTP_OR_HTTPS,
        /** 只接受 {@code https:}（xAI）。 */
        HTTPS_ONLY
    }

    /** docs/19 #43：{@code verification_uri_complete} 的取用方式。 */
    public enum CompleteUriMode {
        /** 忽略 complete URI，只用 {@code verification_uri}（GitHub Copilot）。 */
        IGNORE,
        /** complete URI 可信时优先使用，否则回落 {@code verification_uri}（xAI）。 */
        PREFER,
        /** 两条 URI 都必须可信，展示 complete URI（Kimi）。 */
        REQUIRE
    }

    /** 标准 RFC 8628 device-code 配置（verification URI 取自设备响应）。 */
    public static DeviceCodeConfig standard(String name, String deviceCodeUrl,
                                            String tokenUrl, String clientId, String scope) {
        return standard(name, deviceCodeUrl, tokenUrl, clientId, scope,
            UriPolicy.HTTP_OR_HTTPS, CompleteUriMode.IGNORE);
    }

    /** docs/19 #43：带 URL 策略的标准 device-code 配置。 */
    public static DeviceCodeConfig standard(String name, String deviceCodeUrl,
                                            String tokenUrl, String clientId, String scope,
                                            UriPolicy uriPolicy, CompleteUriMode completeUriMode) {
        return new DeviceCodeConfig(name, deviceCodeUrl, tokenUrl, clientId, scope,
            "", DeviceAuthStyle.STANDARD, "", "", uriPolicy, completeUriMode);
    }

    /** OpenAI Codex 两段式配置：device 轮询 + PKCE 授权码交换。 */
    public static DeviceCodeConfig codex(String name, String deviceCodeUrl, String tokenUrl,
                                         String clientId, String scope, String verificationUri,
                                         String exchangeUrl, String exchangeRedirectUri) {
        return new DeviceCodeConfig(name, deviceCodeUrl, tokenUrl, clientId, scope,
            verificationUri, DeviceAuthStyle.OPENAI_CODEX, exchangeUrl, exchangeRedirectUri,
            UriPolicy.HTTP_OR_HTTPS, CompleteUriMode.IGNORE);
    }
}
