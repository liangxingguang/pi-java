package com.pijava.mcp;

import org.jspecify.annotations.Nullable;

/**
 * Supplies bearer tokens to an MCP HTTP transport and may refresh them after a
 * challenge (pi auth-provider.ts:3-16).
 *
 * <p>Implemented by the OAuth flow ({@code oauth.adaptOAuthProvider}); the
 * transport works without one when the server needs no authentication.</p>
 */
public interface AuthProvider {

    /** Current bearer token, if any. */
    @Nullable String token() throws Exception;

    /** Handle a 401 or an insufficient-scope 403 challenge. */
    default void onUnauthorized(Context context) throws Exception {
    }

    /**
     * Information about the failed request.
     *
     * @param status HTTP status of the challenge
     * @param wwwAuthenticate the challenge header, empty when absent
     * @param serverUrl the MCP server URL
     * @param token the token the request carried, when the provider supplied one
     * @param fetch a buffered fetch usable for the authorization flow
     */
    record Context(
            int status,
            String wwwAuthenticate,
            String serverUrl,
            @Nullable String token,
            @Nullable McpFetch fetch) {
    }
}
