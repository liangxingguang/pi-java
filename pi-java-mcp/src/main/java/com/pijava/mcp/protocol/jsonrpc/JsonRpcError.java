package com.pijava.mcp.protocol.jsonrpc;

import org.jspecify.annotations.Nullable;

/**
 * JSON-RPC error object ({@code jsonrpc.ts:16-20}).
 *
 * @param code    error code
 * @param message error message
 * @param data    optional error data
 */
public record JsonRpcError(int code, String message, @Nullable Object data) {
}
