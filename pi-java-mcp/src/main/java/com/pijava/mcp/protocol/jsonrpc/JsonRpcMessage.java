package com.pijava.mcp.protocol.jsonrpc;

import org.jspecify.annotations.Nullable;

/**
 * One JSON-RPC message ({@code jsonrpc.ts:3-35}).
 */
public sealed interface JsonRpcMessage
        permits JsonRpcMessage.Request, JsonRpcMessage.Notification, JsonRpcMessage.Response {

    /**
     * Request.
     *
     * @param id     request id
     * @param method method name
     * @param params optional params, passed through untouched
     */
    record Request(JsonRpcId id, String method, @Nullable Object params) implements JsonRpcMessage {
    }

    /**
     * Notification (no id).
     *
     * @param method method name
     * @param params optional params, passed through untouched
     */
    record Notification(String method, @Nullable Object params) implements JsonRpcMessage {
    }

    /** Response to a request. */
    sealed interface Response extends JsonRpcMessage permits Response.Success, Response.Error {

        /**
         * Success response.
         *
         * @param id     the request id
         * @param result result value
         */
        record Success(JsonRpcId id, Object result) implements Response {
        }

        /**
         * Error response.
         *
         * @param id    the request id
         * @param error error object
         */
        record Error(JsonRpcId id, JsonRpcError error) implements Response {
        }
    }
}
