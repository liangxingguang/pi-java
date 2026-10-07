package com.pijava.mcp.oauth;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * RFC 8414 authorization-server metadata (pi types.ts:16-30). Only the fields
 * pi names are typed; other document fields are kept verbatim in {@code extension}.
 *
 * @param issuer server's issuer identifier
 * @param authorizationEndpoint authorization endpoint
 * @param tokenEndpoint token endpoint
 * @param registrationEndpoint dynamic client registration endpoint
 * @param scopesSupported supported scopes
 * @param responseTypesSupported supported response types (required by RFC 8414)
 * @param grantTypesSupported supported grant types
 * @param tokenEndpointAuthMethodsSupported token endpoint authentication methods
 * @param codeChallengeMethodsSupported supported PKCE methods
 * @param clientIdMetadataDocumentSupported whether {@code client_id} may be a metadata document URL
 * @param authorizationResponseIssParameterSupported whether authorization responses carry {@code iss} (RFC 9207)
 * @param extension all other document fields
 */
public record AuthorizationServerMetadata(
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        @Nullable String registrationEndpoint,
        @Nullable List<String> scopesSupported,
        List<String> responseTypesSupported,
        @Nullable List<String> grantTypesSupported,
        @Nullable List<String> tokenEndpointAuthMethodsSupported,
        @Nullable List<String> codeChallengeMethodsSupported,
        @Nullable Boolean clientIdMetadataDocumentSupported,
        @Nullable Boolean authorizationResponseIssParameterSupported,
        @Nullable Map<String, Object> extension) {
}
