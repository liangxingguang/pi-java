package com.pijava.mcp.transport.http;

import org.jspecify.annotations.Nullable;

/**
 * Supplies credentials for the streamable transport and resolves challenges
 * once (the {@code AuthProvider} shape from auth-provider.ts).
 *
 * <p>Implemented by the OAuth packages (6/7); the transport works without
 * one when the server needs no authentication.</p>
 */
interface AuthProvider {

    /** Current bearer token, if any. */
    @Nullable String token() throws Exception;

    /** Handle a 401 or an insufficient-scope 403 challenge. */
    void onUnauthorized(Context context) throws Exception;

    /** Information about the failed request. */
    record Context(
            int status,
            String wwwAuthenticate,
            String serverUrl,
            @Nullable String token) {
    }
}
