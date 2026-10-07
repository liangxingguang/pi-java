package com.pijava.mcp.oauth;

/**
 * Refusal to send OAuth credentials to a non-HTTPS endpoint (pi errors.ts:28-36).
 */
public class OAuthInsecureEndpointError extends RuntimeException {

    private final String endpoint;

    /** Create with the offending endpoint. */
    public OAuthInsecureEndpointError(String endpoint) {
        super("Refusing to send OAuth credentials to non-HTTPS endpoint " + endpoint);
        this.endpoint = endpoint;
    }

    /** The endpoint credentials would have been sent to. */
    public String endpoint() {
        return endpoint;
    }
}
