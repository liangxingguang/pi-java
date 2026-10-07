package com.pijava.mcp.protocol.jsonrpc;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Standard JSON-RPC error codes ({@code jsonrpc.ts:37-43}).
 */
public enum JsonRpcErrorCode {

    PARSE_ERROR(-32700),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602),
    INTERNAL_ERROR(-32603);

    private final int code;

    JsonRpcErrorCode(int code) {
        this.code = code;
    }

    /** The numeric wire code. */
    @JsonValue
    public int code() {
        return code;
    }

    /** Look up a standard code; non-standard codes yield {@code null}. */
    @JsonCreator
    public static @Nullable JsonRpcErrorCode fromCode(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
