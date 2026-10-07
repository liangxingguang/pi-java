package com.pijava.mcp.oauth;

import java.net.URI;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpFetch;

/**
 * One authorization attempt (pi flow.ts:70-89).
 *
 * @param serverUrl the MCP server URL
 * @param authorizationCode code to exchange, when resuming an authorization
 * @param iss the {@code iss} parameter that delivered the code (RFC 9207)
 * @param scope scope to request
 * @param resourceMetadataUrl explicitly configured protected-resource metadata
 * @param authorizationServerMetadataUrl metadata document used instead of discovery
 * @param fetch HTTP fetch, defaulting to the JDK
 * @param skipIssuerValidation accept authorization server metadata for another issuer
 * @param skipRefresh go straight to the redirect instead of refreshing stored tokens
 */
public record OAuthFlowOptions(
        URI serverUrl,
        @Nullable String authorizationCode,
        @Nullable String iss,
        @Nullable String scope,
        @Nullable URI resourceMetadataUrl,
        @Nullable URI authorizationServerMetadataUrl,
        @Nullable McpFetch fetch,
        boolean skipIssuerValidation,
        boolean skipRefresh) {

    /** Options for a fresh authorization against a server URL. */
    public static OAuthFlowOptions of(URI serverUrl) {
        return new OAuthFlowOptions(serverUrl, null, null, null, null, null, null, false, false);
    }
}
