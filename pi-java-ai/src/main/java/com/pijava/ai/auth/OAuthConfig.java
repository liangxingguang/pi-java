package com.pijava.ai.auth;

import java.util.Map;

/**
 * OAuth2 授权码 + PKCE 流程的 provider 配置（P6-17）。
 *
 * <p>{@code authorizeTemplate} 支持占位符：{@code {redirect_uri}}、
 * {@code {code_challenge}}、{@code {code_challenge_method}}、{@code {client_id}}、
 * {@code {scope}}。token 请求按 {@link TokenRequestStyle} 选择表单或 JSON 编码，
 * {@code tokenExtra} 追加额外字段（如 OpenRouter 的 {@code code_challenge_method}）。</p>
 */
public record OAuthConfig(
    String name,
    String authorizeTemplate,
    String tokenUrl,
    String clientId,
    String scope,
    TokenRequestStyle tokenStyle,
    Map<String, String> tokenExtra
) {

    /** Token 请求编码风格。 */
    public enum TokenRequestStyle { FORM, JSON }

    /**
     * 用标准 OAuth2 授权模板构造配置：authorize URL 追加
     * {@code response_type=code} 与全部占位符参数。
     */
    public static OAuthConfig standard(String name, String authorizeUrl,
                                       String tokenUrl, String clientId, String scope) {
        return new OAuthConfig(name,
            authorizeUrl + "?response_type=code&redirect_uri={redirect_uri}"
                + "&code_challenge={code_challenge}&code_challenge_method=S256"
                + "&client_id={client_id}&scope={scope}",
            tokenUrl, clientId, scope, TokenRequestStyle.FORM, Map.of());
    }

    /** 用 JSON 编码的 token 请求构造配置（如 OpenRouter，仅 code/verifier/extra）。 */
    public static OAuthConfig jsonStyle(String name, String authorizeTemplate,
                                        String tokenUrl, Map<String, String> tokenExtra) {
        return new OAuthConfig(name, authorizeTemplate, tokenUrl, "", "", TokenRequestStyle.JSON,
            Map.copyOf(tokenExtra));
    }
}
