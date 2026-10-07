package com.pijava.mcp.transport.http;

import org.jspecify.annotations.Nullable;

/**
 * The server requires authentication (streamable-http.ts:138-144).
 */
public final class McpAuthRequiredError extends McpHttpError {

    private final @Nullable String wwwAuthenticate;

    /** Create from the challenge header. */
    public McpAuthRequiredError(String body, @Nullable String wwwAuthenticate) {
        super(401, "MCP server requires authentication", body);
        this.wwwAuthenticate = wwwAuthenticate;
    }

    /** The WWW-Authenticate challenge, when present. */
    public @Nullable String wwwAuthenticate() {
        return wwwAuthenticate;
    }
}
