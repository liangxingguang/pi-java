package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

/**
 * OAuth failure carrying a stable error code (pi errors.ts:1-11).
 */
public class OAuthError extends RuntimeException {

    private final String code;
    private final @Nullable String errorUri;

    /** Create with an explicit message and optional error URI. */
    public OAuthError(String code, String message, @Nullable String errorUri) {
        super(message == null || message.isEmpty() ? code : message);
        this.code = code;
        this.errorUri = errorUri;
    }

    /** Create where the message is the code itself. */
    public OAuthError(String code) {
        this(code, code, null);
    }

    /** The machine-readable error code. */
    public String code() {
        return code;
    }

    /** Optional page with human-readable details. */
    public @Nullable String errorUri() {
        return errorUri;
    }
}
