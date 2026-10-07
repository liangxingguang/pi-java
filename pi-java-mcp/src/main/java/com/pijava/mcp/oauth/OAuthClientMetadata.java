package com.pijava.mcp.oauth;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Client metadata registered with an authorization server (pi types.ts:41-58).
 *
 * @param redirectUris redirect URIs this client may use
 * @param tokenEndpointAuthMethod how the client authenticates at the token endpoint
 * @param grantTypes grant types the client may use
 * @param responseTypes response types the client may use
 * @param clientName human-readable client name
 * @param clientUri client home page
 * @param logoUri client logo
 * @param scope scope the client asks for by default
 * @param contacts contact addresses
 * @param tosUri terms of service
 * @param policyUri privacy policy
 * @param jwksUri client JWKS location
 * @param jwks inline client JWKS
 * @param softwareId software identifier
 * @param softwareVersion software version
 * @param softwareStatement software statement
 */
public record OAuthClientMetadata(
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @JsonProperty("token_endpoint_auth_method") @Nullable String tokenEndpointAuthMethod,
        @JsonProperty("grant_types") @Nullable List<String> grantTypes,
        @JsonProperty("response_types") @Nullable List<String> responseTypes,
        @JsonProperty("client_name") @Nullable String clientName,
        @JsonProperty("client_uri") @Nullable String clientUri,
        @JsonProperty("logo_uri") @Nullable String logoUri,
        @JsonProperty("scope") @Nullable String scope,
        @JsonProperty("contacts") @Nullable List<String> contacts,
        @JsonProperty("tos_uri") @Nullable String tosUri,
        @JsonProperty("policy_uri") @Nullable String policyUri,
        @JsonProperty("jwks_uri") @Nullable String jwksUri,
        @JsonProperty("jwks") @Nullable Object jwks,
        @JsonProperty("software_id") @Nullable String softwareId,
        @JsonProperty("software_version") @Nullable String softwareVersion,
        @JsonProperty("software_statement") @Nullable String softwareStatement) {
}
