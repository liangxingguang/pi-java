package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

/**
 * An authorization server document or response named an issuer other than the
 * one discovery asked it for (pi errors.ts:13-26).
 */
public class OAuthIssuerMismatchError extends RuntimeException {

    private final String expected;
    /** Null when an authorization response lacks the promised {@code iss} parameter (RFC 9207). */
    private final @Nullable String received;

    /** Create with the expected issuer and the received one (or null). */
    public OAuthIssuerMismatchError(String expected, @Nullable String received) {
        super("OAuth issuer mismatch: expected " + quote(expected)
                + ", received " + (received == null ? "none" : quote(received)));
        this.expected = expected;
        this.received = received;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    /** The issuer discovery expected. */
    public String expected() {
        return expected;
    }

    /** The issuer that was named, or null for none. */
    public @Nullable String received() {
        return received;
    }
}
