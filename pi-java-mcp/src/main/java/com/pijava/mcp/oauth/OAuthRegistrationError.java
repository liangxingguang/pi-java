package com.pijava.mcp.oauth;

/**
 * Dynamic client registration failed (pi errors.ts:38-48).
 */
public class OAuthRegistrationError extends RuntimeException {

    private final int status;
    private final String body;

    /** Create with the HTTP status and response body. */
    public OAuthRegistrationError(int status, String body) {
        super("OAuth dynamic client registration failed with status " + status + ": " + body);
        this.status = status;
        this.body = body;
    }

    /** The HTTP status returned by the registration endpoint. */
    public int status() {
        return status;
    }

    /** The response body. */
    public String body() {
        return body;
    }
}
