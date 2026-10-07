package com.pijava.mcp.protocol.jsonrpc;

/**
 * Thrown when a request is made against a client whose connection is closed
 * ({@code jsonrpc.ts:57-62}).
 */
public class McpConnectionClosedError extends RuntimeException {

    /** Default error. */
    public McpConnectionClosedError() {
        this("MCP connection closed");
    }

    /** Error with a specific message. */
    public McpConnectionClosedError(String message) {
        super(message);
    }
}
