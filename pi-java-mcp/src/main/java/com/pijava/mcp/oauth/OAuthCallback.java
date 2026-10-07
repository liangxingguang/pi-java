package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

/**
 * An authorization response (pi callback.ts:3-7).
 *
 * @param code the authorization code
 * @param state the state that matched a pending request
 * @param iss the {@code iss} parameter, when the server sent one (RFC 9207)
 */
public record OAuthCallback(String code, String state, @Nullable String iss) {
}
