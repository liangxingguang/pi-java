package com.pijava.mcp;

import java.util.Map;

import com.pijava.mcp.protocol.jsonrpc.JsonRpcErrorCode;
import com.pijava.mcp.protocol.jsonrpc.JsonRpcMessages;
import com.pijava.mcp.protocol.jsonrpc.McpError;

/**
 * Handles messages a transport delivers to an {@link McpClient}
 * (client.ts:460-546).
 */
final class McpClientInbox {

    private final McpClient client;

    McpClientInbox(McpClient client) {
        this.client = client;
    }

    /** Classify and dispatch one parsed JSON value (client.ts:460-474). */
    void handleMessage(Object raw) {
        if (JsonRpcMessages.isResponse(raw)) {
            handleResponse(McpClientWires.asObjectMap(raw));
        } else if (JsonRpcMessages.isRequest(raw)) {
            handleRequest(McpClientWires.asObjectMap(raw));
        } else if (JsonRpcMessages.isNotification(raw)) {
            handleNotification(McpClientWires.asObjectMap(raw));
        } else {
            client.reportError(new McpError(
                    JsonRpcErrorCode.INVALID_REQUEST, "Received invalid JSON-RPC message"));
        }
    }

    /** client.ts:476-485 */
    private void handleResponse(Map<String, Object> map) {
        long id = ((Number) map.get("id")).longValue();
        var entry = client.pendingEntry(id);
        if (entry == null) {
            client.reportError(new IllegalStateException(
                    "Received response for unknown MCP request " + id));
            return;
        }
        client.removePendingEntry(id, entry);
        if (map.get("error") instanceof Map<?, ?> error) {
            entry.future().completeExceptionally(new McpError(
                    ((Number) error.get("code")).intValue(),
                    (String) error.get("message"), error.get("data")));
        } else {
            entry.future().complete(map.get("result"));
        }
    }

    /** client.ts:487-517 */
    private void handleRequest(Map<String, Object> map) {
        var rawId = map.get("id");
        var method = (String) map.get("method");
        var params = map.get("params");
        var registrations = client.registrations();
        var handler = registrations.requestHandler(method);
        if (handler == null) {
            sendError(rawId, JsonRpcErrorCode.METHOD_NOT_FOUND, "Method not found: " + method);
            return;
        }
        var signal = client.addIncoming(rawId);
        Thread.startVirtualThread(() -> {
            Object result = null;
            Throwable failure = null;
            try {
                result = handler.handle(params, signal);
                if (result instanceof java.util.concurrent.CompletionStage<?> stage) {
                    result = stage.toCompletableFuture().get();
                }
            } catch (Throwable error) {
                failure = error;
            }
            var transport = client.currentTransport();
            try {
                if (transport != null) {
                    if (failure == null) {
                        transport.send(McpClientWires.response(
                                rawId, result == null ? Map.of() : McpClientWires.toWire(result)));
                    } else {
                        transport.send(errorWire(rawId, failure));
                    }
                }
            } catch (Throwable sendError) {
                client.reportError(sendError);
            } finally {
                client.removeIncoming(rawId);
            }
        });
    }

    /** client.ts:519-529 */
    private void handleNotification(Map<String, Object> map) {
        var method = (String) map.get("method");
        var params = map.get("params");
        if ("notifications/progress".equals(method)) {
            handleProgress(params);
        } else if ("notifications/cancelled".equals(method)) {
            handleCancelled(params);
        }
        for (var listener : client.registrations().notificationListenerSnapshot(method)) {
            try {
                listener.accept(params);
            } catch (Throwable error) {
                client.reportError(error);
            }
        }
    }

    /** client.ts:531-542 */
    private void handleProgress(Object raw) {
        if (!(raw instanceof Map<?, ?> params)
                || !(params.get("progressToken") instanceof Number tokenNumber)
                || !(params.get("progress") instanceof Number)) {
            return;
        }
        long token = tokenNumber.longValue();
        var requestId = client.progressRequest(token);
        var entry = requestId == null ? null : client.pendingEntry(requestId);
        if (entry != null) {
            client.rearmTimeout(requestId, entry.timeoutMs());
        }
        if (entry == null || entry.onProgress() == null) {
            return;
        }
        var notification = McpJson.mapper().convertValue(
                params, com.pijava.mcp.protocol.ProgressNotification.class);
        try {
            entry.onProgress().accept(notification);
        } catch (Throwable error) {
            client.reportError(error);
        }
    }

    /** client.ts:544-546 */
    private void handleCancelled(Object raw) {
        if (raw instanceof Map<?, ?> params && params.get("requestId") != null) {
            var signal = client.findIncoming(params.get("requestId"));
            if (signal != null) {
                signal.abort();
            }
        }
    }

    /** Send a method-not-found response (client.ts:492-499). */
    private void sendError(Object rawId, JsonRpcErrorCode code, String message) {
        var transport = client.currentTransport();
        if (transport == null) {
            return;
        }
        try {
            transport.send(McpClientWires.error(rawId, code.code(), message, null));
        } catch (Throwable sendError) {
            client.reportError(sendError);
        }
    }

    /** Build the error wire for a handler failure (client.ts:507-513). */
    private static Map<String, Object> errorWire(Object rawId, Throwable failure) {
        int code = JsonRpcErrorCode.INTERNAL_ERROR.code();
        String message = String.valueOf(failure.getMessage());
        Object data = null;
        if (failure instanceof McpError mcpError) {
            code = mcpError.code();
            message = mcpError.getMessage();
            data = mcpError.data();
        }
        return McpClientWires.error(rawId, code, message, data);
    }
}
