package com.pijava.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import com.pijava.mcp.protocol.McpVersion;
import com.pijava.mcp.protocol.jsonrpc.JsonRpcErrorCode;
import com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError;
import com.pijava.mcp.protocol.jsonrpc.McpError;
import com.pijava.mcp.transport.McpTransport;

/**
 * Scripted in-process transport for {@link McpClient} tests: records wire
 * messages and responds to requests per registered handlers on a virtual
 * thread, like the in-memory pair in pi's tests.
 */
final class RecordingTransport implements McpTransport {

    private final List<Map<String, Object>> recorded = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Object, Object>> handlers = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Consumer<Object>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();
    private volatile boolean started;
    private volatile boolean closed;

    /** Recorded wire messages, in send order. */
    List<Map<String, Object>> messages() {
        return recorded;
    }

    /** Register a request handler. */
    void setHandler(String method, Function<Object, Object> handler) {
        handlers.put(method, handler);
    }

    /** Deliver a server-to-client message (request or notification). */
    void serverSends(Object raw) {
        for (var listener : messageListeners) {
            listener.accept(raw);
        }
    }

    /** Emit a transport error. */
    void emitTransportError(Throwable error) {
        for (var listener : errorListeners) {
            listener.accept(error);
        }
    }

    /** Create with the default initialize handler, ready for connect. */
    static RecordingTransport create() {
        var transport = new RecordingTransport();
        transport.setHandler("initialize", params -> defaultInitialize());
        return transport;
    }

    /** Default initialize answer (test-server, latest version). */
    static Map<String, Object> defaultInitialize() {
        var result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", McpVersion.LATEST);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", true)));
        result.put("serverInfo", Map.of("name", "test-server", "version", "1.0.0"));
        result.put("instructions", "Use test tools.");
        return result;
    }

    @Override
    public void start() {
        if (started) {
            throw new IllegalStateException("transport already started");
        }
        started = true;
    }

    @Override
    public void send(Map<String, Object> message) {
        if (closed) {
            throw new McpConnectionClosedError();
        }
        var copy = new LinkedHashMap<String, Object>(message);
        recorded.add(copy);
        if (copy.get("id") != null) {
            scheduleResponse(copy);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (var listener : closeListeners) {
            listener.run();
        }
    }

    @Override
    public Runnable onMessage(Consumer<Object> listener) {
        messageListeners.add(listener);
        return () -> messageListeners.remove(listener);
    }

    @Override
    public Runnable onError(Consumer<Throwable> listener) {
        errorListeners.add(listener);
        return () -> errorListeners.remove(listener);
    }

    @Override
    public Runnable onClose(Runnable listener) {
        closeListeners.add(listener);
        return () -> closeListeners.remove(listener);
    }

    private void scheduleResponse(Map<String, Object> request) {
        var rawId = request.get("id");
        var method = (String) request.get("method");
        var params = request.get("params");
        Thread.startVirtualThread(() -> {
            var handler = handlers.get(method);
            if (handler == null) {
                serverSends(McpClientWires.error(rawId, JsonRpcErrorCode.METHOD_NOT_FOUND.code(),
                        "Method not found: " + method, null));
                return;
            }
            try {
                var output = handler.apply(params);
                if (output instanceof CompletionStage<?> stage) {
                    output = ((CompletableFuture<?>) stage).get();
                }
                serverSends(McpClientWires.response(rawId, output == null ? Map.of() : output));
            } catch (Throwable failure) {
                var cause = failure;
                if (failure instanceof java.util.concurrent.ExecutionException && failure.getCause() != null) {
                    cause = failure.getCause();
                }
                int code = JsonRpcErrorCode.INTERNAL_ERROR.code();
                String message = String.valueOf(cause.getMessage());
                Object data = null;
                if (cause instanceof McpError mcpError) {
                    code = mcpError.code();
                    message = mcpError.getMessage();
                    data = mcpError.data();
                }
                serverSends(McpClientWires.error(rawId, code, message, data));
            }
        });
    }
}
