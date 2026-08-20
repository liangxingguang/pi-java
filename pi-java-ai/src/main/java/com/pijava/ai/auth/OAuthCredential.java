package com.pijava.ai.auth;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * OAuth 登录结果凭证（P6-17）。
 *
 * <p>持有一个 provider 的 access/refresh token 对与过期时间。permanent 型
 * provider（如 OpenRouter 换回长期 API key）用 {@code expiresAtEpochSec == Long.MAX_VALUE}
 * 表示。</p>
 */
public record OAuthCredential(
    String accessToken,
    String refreshToken,
    long expiresAtEpochSec,
    String baseUrl
) {

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
