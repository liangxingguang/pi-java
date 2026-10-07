package com.pijava.mcp.oauth;

import java.net.URI;

import org.jspecify.annotations.Nullable;

/**
 * Fields extracted from a {@code WWW-Authenticate} challenge (pi types.ts:83-88).
 *
 * @param resourceMetadataUrl hinted protected-resource metadata document
 * @param scope requested scope
 * @param error machine-readable error, e.g. {@code invalid_token}
 * @param errorDescription human-readable error description
 */
public record OAuthChallenge(
        @Nullable URI resourceMetadataUrl,
        @Nullable String scope,
        @Nullable String error,
        @Nullable String errorDescription) {

    /** A challenge carrying no information. */
    public static OAuthChallenge empty() {
        return new OAuthChallenge(null, null, null, null);
    }
}
