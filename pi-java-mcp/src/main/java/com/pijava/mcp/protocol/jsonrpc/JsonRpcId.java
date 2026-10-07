package com.pijava.mcp.protocol.jsonrpc;

import org.jspecify.annotations.Nullable;

/**
 * JSON-RPC id: a string or a finite number ({@code jsonrpc.ts:1,89-91}).
 *
 * <p>Used only for request correlation inside the client; it is not serialized
 * directly — wire messages carry the raw value so an integer id stays
 * {@code 1}, never {@code 1.0}.</p>
 */
public sealed interface JsonRpcId permits JsonRpcId.Text, JsonRpcId.Number {

    /** Convert a parsed JSON value ({@code String} or finite {@code Number}), else {@code null}. */
    static @Nullable JsonRpcId of(@Nullable Object raw) {
        return switch (raw) {
            case String s -> new Text(s);
            case java.lang.Number n -> Double.isFinite(n.doubleValue()) ? new Number(n.doubleValue()) : null;
            case null -> null;
            default -> null;
        };
    }

    /** String id. */
    record Text(String value) implements JsonRpcId {
    }

    /** Numeric id; finite by construction. */
    record Number(double value) implements JsonRpcId {
    }
}
