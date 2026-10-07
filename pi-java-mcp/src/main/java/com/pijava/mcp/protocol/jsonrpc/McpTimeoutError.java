package com.pijava.mcp.protocol.jsonrpc;

/**
 * Thrown when an MCP request exceeds its timeout ({@code jsonrpc.ts:64-72}).
 */
public class McpTimeoutError extends RuntimeException {

    private final long timeoutMs;

    /** Create for a timeout of {@code timeoutMs} milliseconds. */
    public McpTimeoutError(long timeoutMs) {
        super("MCP request timed out after " + timeoutMs + "ms");
        this.timeoutMs = timeoutMs;
    }

    /** The configured timeout in milliseconds. */
    public long timeoutMs() {
        return timeoutMs;
    }
}
