package com.pijava.mcp.protocol.jsonrpc;

/**
 * Thrown when an MCP request is aborted ({@code jsonrpc.ts:74-79}).
 */
public class McpAbortError extends RuntimeException {

    /** Default error. */
    public McpAbortError() {
        this("MCP request aborted");
    }

    /** Error with a specific message. */
    public McpAbortError(String message) {
        super(message);
    }
}
