package com.pijava.mcp.transport.http;

/**
 * HTTP failure while talking to an MCP server (streamable-http.ts:126-136).
 */
public class McpHttpError extends RuntimeException {

    private final int status;
    private final String body;

    /** Create an error. */
    public McpHttpError(int status, String message) {
        this(status, message, "");
    }

    /** Create an error carrying the response body. */
    public McpHttpError(int status, String message, String body) {
        super(message);
        this.status = status;
        this.body = body;
    }

    /** The HTTP status code. */
    public int status() {
        return status;
    }

    /** The response body, possibly truncated, possibly empty. */
    public String body() {
        return body;
    }
}
