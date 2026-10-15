package com.pijava.ai.auth;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OAuth 登录结果凭证（P6-17）。
 *
 * <p>持有一个 provider 的 access/refresh token 对与过期时间。permanent 型
 * provider（如 OpenRouter 换回长期 API key）用 {@code expiresAtEpochSec == Long.MAX_VALUE}
 * 表示。{@code clientId}/{@code scopes} 是 ChatGPT 动态客户端流的签发客户端 ID
 * 与授予 scope 列表（pi {@code OAuthCredential.clientId/scopes}）；其余流为 null/空，
 * 旧 {@code auth-oauth.json} 缺这两字段时反序列化也是 null/空。</p>
 */
public record OAuthCredential(
    String accessToken,
    String refreshToken,
    long expiresAtEpochSec,
    String baseUrl,
    /** ChatGPT：每次登录由提供方签发的客户端 ID（刷新时必须回传）。 */
    String clientId,
    /** ChatGPT：token 响应里实际授予的 scope。 */
    List<String> scopes
) {

    /** Jackson 显式钉住六参规范构造器（另有四参便捷构造器，避免歧义）。 */
    @JsonCreator
    public OAuthCredential(
        @JsonProperty("accessToken") String accessToken,
        @JsonProperty("refreshToken") String refreshToken,
        @JsonProperty("expiresAtEpochSec") long expiresAtEpochSec,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("clientId") String clientId,
        @JsonProperty("scopes") List<String> scopes
    ) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAtEpochSec = expiresAtEpochSec;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        // 旧文件无 scopes 字段时 Jackson 给 null（FAIL_ON_UNKNOWN_PROPERTIES=false），归一为空表。
        this.scopes = scopes == null ? List.of() : scopes;
    }

    /** 四参形态：无 clientId/scopes（anthropic/openrouter/device 流）。 */
    public OAuthCredential(String accessToken, String refreshToken, long expiresAtEpochSec,
                           String baseUrl) {
        this(accessToken, refreshToken, expiresAtEpochSec, baseUrl, null, List.of());
    }

    /** 长期有效凭证（无 refresh token、永不过期）。 */
    public static OAuthCredential permanent(String accessToken) {
        return new OAuthCredential(accessToken, "", Long.MAX_VALUE, null);
    }

    /** 是否已过期（expiresAt 为 0 视为未知，不判过期）。 */
    @JsonIgnore
    public boolean isExpired() {
        return expiresAtEpochSec > 0
            && expiresAtEpochSec < Instant.now().getEpochSecond();
    }
}
