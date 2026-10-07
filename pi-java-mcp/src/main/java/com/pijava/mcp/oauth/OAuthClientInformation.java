package com.pijava.mcp.oauth;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Registered client identity (pi types.ts:60-68, plus the metadata a full
 * registration response echoes back). pi models the mixed/full split as a
 * union type; the flow only ever reads {@code clientId}, {@code clientSecret},
 * and {@code tokenEndpointAuthMethod}, so one record carries both shapes.
 *
 * @param clientId registered client identifier
 * @param clientSecret client secret, when the server issued one
 * @param clientIdIssuedAt issuance time, seconds since the epoch
 * @param clientSecretExpiresAt secret expiry, seconds since the epoch
 * @param tokenEndpointAuthMethod authentication method the client prefers
 * @param redirectUris redirect URIs echoed by the registration response
 * @param extension all other document fields
 */
public record OAuthClientInformation(
        @JsonProperty("client_id") String clientId,
        @JsonProperty("client_secret") @Nullable String clientSecret,
        @JsonProperty("client_id_issued_at") @Nullable Integer clientIdIssuedAt,
        @JsonProperty("client_secret_expires_at") @Nullable Integer clientSecretExpiresAt,
        @JsonProperty("token_endpoint_auth_method") @Nullable String tokenEndpointAuthMethod,
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @Nullable Map<String, Object> extension) {
}
