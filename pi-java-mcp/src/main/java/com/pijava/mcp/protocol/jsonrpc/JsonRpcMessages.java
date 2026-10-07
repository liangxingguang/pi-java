package com.pijava.mcp.protocol.jsonrpc;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Structural classification and parsing of JSON-RPC messages
 * ({@code jsonrpc.ts:81-113}).
 */
public final class JsonRpcMessages {

    private JsonRpcMessages() {
    }

    /** Whether a parsed value is a plain object ({@code jsonrpc.ts:81-83}). */
    public static boolean isObject(@Nullable Object value) {
        return value instanceof Map<?, ?>;
    }

    /** Whether a value is a legal JSON-RPC id ({@code jsonrpc.ts:89-91}). */
    public static boolean isJsonRpcId(@Nullable Object value) {
        return JsonRpcId.of(value) != null;
    }

    /** Whether an object is a JSON-RPC request ({@code jsonrpc.ts:93-97}). */
    public static boolean isRequest(@Nullable Object value) {
        return value instanceof Map<?, ?> m
                && "2.0".equals(m.get("jsonrpc"))
                && isJsonRpcId(m.get("id"))
                && m.get("method") instanceof String;
    }

    /** Whether an object is a JSON-RPC notification ({@code jsonrpc.ts:99-101}). */
    public static boolean isNotification(@Nullable Object value) {
        return value instanceof Map<?, ?> m
                && "2.0".equals(m.get("jsonrpc"))
                && !m.containsKey("id")
                && m.get("method") instanceof String;
    }

    /** Whether an object is a JSON-RPC response ({@code jsonrpc.ts:103-108}). */
    public static boolean isResponse(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> m)
                || !"2.0".equals(m.get("jsonrpc"))
                || !isJsonRpcId(m.get("id"))) {
            return false;
        }
        if (m.containsKey("result")) {
            return !m.containsKey("error");
        }
        if (!m.containsKey("error") || !(m.get("error") instanceof Map<?, ?> error)) {
            return false;
        }
        return error.get("code") instanceof Number && error.get("message") instanceof String;
    }

    /**
     * Parse a JSON value into a JSON-RPC message, or throw
     * {@link McpError} with {@link JsonRpcErrorCode#INVALID_REQUEST}
     * ({@code jsonrpc.ts:110-113}).
     */
    public static JsonRpcMessage parse(@Nullable Object value) {
        if (isRequest(value)) {
            return toRequest(asMap(value));
        }
        if (isNotification(value)) {
            return toNotification(asMap(value));
        }
        if (isResponse(value)) {
            return toResponse(asMap(value));
        }
        throw new McpError(JsonRpcErrorCode.INVALID_REQUEST, "Invalid JSON-RPC message");
    }

    private static Map<?, ?> asMap(Object value) {
        return (Map<?, ?>) value;
    }

    private static JsonRpcMessage.Request toRequest(Map<?, ?> map) {
        var id = Objects.requireNonNull(JsonRpcId.of(map.get("id")));
        return new JsonRpcMessage.Request(id, (String) map.get("method"), map.get("params"));
    }

    private static JsonRpcMessage.Notification toNotification(Map<?, ?> map) {
        return new JsonRpcMessage.Notification((String) map.get("method"), map.get("params"));
    }

    private static JsonRpcMessage.Response toResponse(Map<?, ?> map) {
        var id = Objects.requireNonNull(JsonRpcId.of(map.get("id")));
        if (map.containsKey("error")) {
            var error = (Map<?, ?>) map.get("error");
            var code = ((Number) error.get("code")).intValue();
            var jsonRpcError = new JsonRpcError(code, (String) error.get("message"), error.get("data"));
            return new JsonRpcMessage.Response.Error(id, jsonRpcError);
        }
        return new JsonRpcMessage.Response.Success(id, map.get("result"));
    }
}
