package com.pijava.mcp.protocol.jsonrpc;

import org.jspecify.annotations.Nullable;

/**
 * Error carrying a JSON-RPC code and optional data ({@code jsonrpc.ts:45-55}).
 */
public class McpError extends RuntimeException {

    private final int code;
    private final @Nullable Object data;

    /** Create from an arbitrary numeric code. */
    public McpError(int code, String message, @Nullable Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    /** Create from a standard code. */
    public McpError(JsonRpcErrorCode code, String message) {
        this(code.code(), message, null);
    }

    /** The error code. */
    public int code() {
        return code;
    }

    /** Optional error data. */
    public @Nullable Object data() {
        return data;
    }
}
