package com.pijava.mcp.transport.http;

/**
 * The server no longer knows the session (streamable-http.ts:146-151):
 * a 404 while a session id was assigned.
 */
public final class McpSessionExpiredError extends McpHttpError {

    /** Create an expired-session error. */
    public McpSessionExpiredError(String body) {
        super(404, "MCP session expired", body);
    }
}
