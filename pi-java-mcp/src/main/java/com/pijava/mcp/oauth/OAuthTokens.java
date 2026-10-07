package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token endpoint response (pi types.ts:32-39).
 *
 * @param accessToken the bearer token
 * @param tokenType token type, e.g. {@code Bearer}
 * @param expiresIn lifetime in seconds
 * @param scope scope the grant carries
 * @param refreshToken refresh token, when one was issued
 * @param idToken OpenID Connect ID token
 */
public record OAuthTokens(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") @Nullable Integer expiresIn,
        @JsonProperty("scope") @Nullable String scope,
        @JsonProperty("refresh_token") @Nullable String refreshToken,
        @JsonProperty("id_token") @Nullable String idToken) {
}
