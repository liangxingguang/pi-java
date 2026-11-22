package com.pijava.mcp.oauth;

import java.net.URI;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpFetch;

/**
 * One authorization attempt (pi flow.ts:70-105).
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
 * @param refreshOnly only refresh the stored tokens: return {@code REDIRECT} instead of registering
 *                    a client or preparing an authorization request, so a pending sign-in (its
 *                    {@code state} and PKCE verifier) stays usable (pi {@code flow.ts:92-96})
 * @param authorizationRedirectUrl with {@code authorizationCode}: the redirect URI the authorization
 *                    request used. The exchange fails before sending the code when the flow would
 *                    send another one (RFC 6749 §4.1.3 requires the same; pi {@code flow.ts:97-101})
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
        boolean skipRefresh,
        boolean refreshOnly,
        @Nullable String authorizationRedirectUrl) {

    /** Options for a fresh authorization against a server URL. */
    public static OAuthFlowOptions of(URI serverUrl) {
        return new OAuthFlowOptions(serverUrl, null, null, null, null, null, null,
                false, false, false, null);
    }

    /** The same options, marked as a refresh-only run (never registers a client or redirects). */
    public OAuthFlowOptions asRefreshOnly() {
        return new OAuthFlowOptions(serverUrl, authorizationCode, iss, scope, resourceMetadataUrl,
                authorizationServerMetadataUrl, fetch, skipIssuerValidation, skipRefresh, true,
                authorizationRedirectUrl);
    }
}
