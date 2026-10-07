package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Everything persisted for one MCP server (pi provider.ts:10-19).
 *
 * @param serverUrl the MCP server this state belongs to
 * @param clientInformation registered or configured client identity
 * @param tokens stored tokens
 * @param tokensExpireAt when the access token expires, epoch milliseconds
 * @param codeVerifier PKCE verifier of the pending authorization
 * @param oauthState CSRF state of the pending authorization
 * @param discovery cached discovery result
 */
public record McpOAuthState(
        @JsonProperty("serverUrl") String serverUrl,
        @JsonProperty("clientInformation") @Nullable OAuthClientInformation clientInformation,
        @JsonProperty("tokens") @Nullable OAuthTokens tokens,
        @JsonProperty("tokensExpireAt") @Nullable Long tokensExpireAt,
        @JsonProperty("codeVerifier") @Nullable String codeVerifier,
        @JsonProperty("oauthState") @Nullable String oauthState,
        @JsonProperty("discovery") @Nullable OAuthDiscoveryState discovery) {
}
