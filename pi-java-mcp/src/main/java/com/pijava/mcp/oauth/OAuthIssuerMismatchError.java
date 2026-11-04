package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

/**
 * An authorization server document or response named an issuer other than the
 * one discovery asked it for (pi errors.ts:13-26).
 */
public class OAuthIssuerMismatchError extends RuntimeException {

    /** Null when there was no metadata naming an expected issuer. */
    private final @Nullable String expected;
    /** Null when an authorization response lacks the promised {@code iss} parameter (RFC 9207). */
    private final @Nullable String received;

    /** Create with the expected issuer and the received one (or null). */
    public OAuthIssuerMismatchError(@Nullable String expected, @Nullable String received) {
        super("OAuth issuer mismatch: expected " + (expected == null ? "none" : quote(expected))
                + ", received " + (received == null ? "none" : quote(received)));
        this.expected = expected;
        this.received = received;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    /** The issuer discovery expected, or null when it had no metadata. */
    public @Nullable String expected() {
        return expected;
    }

    /** The issuer that was named, or null for none. */
    public @Nullable String received() {
        return received;
    }
}
