package com.pijava.mcp.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.pijava.mcp.protocol.McpVersion;

/**
 * A scripted MCP server: answers requests, counts them, can fail chosen ones the way the HTTP
 * transport does, and hands out a fresh transport per connection.
 */
final class ScriptedServer {

    private final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Object, Object>> handlers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, Deque<Throwable>> oneShot = new ConcurrentHashMap<>();
    private final Map<String, Throwable> permanent = new ConcurrentHashMap<>();
    private final List<ScriptedTransport> peers = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Object>> outstanding = new ConcurrentHashMap<>();
    private final AtomicLong serverRequestId = new AtomicLong();

    /** Open a new transport onto this server. */
    ScriptedTransport connect() {
        var transport = new ScriptedTransport(this);
        peers.add(transport);
        return transport;
    }

    /** Every wire message the client sent, across all its connections. */
    List<Map<String, Object>> sent() {
        return new ArrayList<>(sent);
    }

    /** How many requests of {@code method} the client sent. */
    int calls(String method) {
        var counter = calls.get(method);
        return counter == null ? 0 : counter.get();
    }

    /** Answer {@code method} with {@code handler}'s result. */
    void answer(String method, Function<Object, Object> handler) {
        handlers.put(method, handler);
    }

    /** Fail the next request of {@code method} before it reaches the server. */
    void failOnce(String method, Throwable error) {
        oneShot.computeIfAbsent(method, key -> new ArrayDeque<>()).add(error);
    }

    /** Fail every request of {@code method}. */
    void failAlways(String method, Throwable error) {
        permanent.put(method, error);
    }

    /** Push a {@code method} notification to every connected client. */
    void notifyClient(String method, Object params) {
        var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        for (var peer : peers) {
            peer.deliver(message);
        }
    }

    /** Ask the most recent client a request and wait for its answer. */
    Object requestFromClient(String method, Object params) throws Exception {
        var id = "s" + serverRequestId.incrementAndGet();
        var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        var answer = new CompletableFuture<Object>();
        outstanding.put(id, answer);
        for (var peer : peers) {
            peer.deliver(message);
        }
        return answer.get(5, TimeUnit.SECONDS);
    }

    /** The standard initialize answer. */
    static Map<String, Object> initializeResult(boolean tools, boolean resources) {
        var capabilities = new LinkedHashMap<String, Object>();
        if (tools) {
            capabilities.put("tools", Map.of("listChanged", true));
        }
        if (resources) {
            capabilities.put("resources", Map.of("listChanged", true));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", McpVersion.LATEST);
        result.put("capabilities", capabilities);
        result.put("serverInfo", Map.of("name", "scripted", "version", "1.0"));
        result.put("instructions", "  Scripted server.  ");
        return result;
    }

    /** One tool entry for {@code tools/list}. */
    static Map<String, Object> tool(String name) {
        return Map.of("name", name, "inputSchema", Map.of("type", "object"));
    }

    /** What one transport's {@code send} does. */
    void onSend(Map<String, Object> message, ScriptedTransport peer) {
        var copy = new LinkedHashMap<String, Object>(message);
        sent.add(copy);
        var id = copy.get("id");
        if (id == null) {
            return;
        }
        var method = copy.get("method");
        if (!(method instanceof String name)) {
            var answer = outstanding.remove(String.valueOf(id));
            if (answer != null) {
                answer.complete(copy.get("result"));
            }
            return;
        }
        calls.computeIfAbsent(name, key -> new AtomicInteger()).incrementAndGet();
        var permanentFailure = permanent.get(name);
        if (permanentFailure != null) {
            throw asRuntime(permanentFailure);
        }
        var queue = oneShot.get(name);
        if (queue != null && !queue.isEmpty()) {
            throw asRuntime(queue.poll());
        }
        var handler = handlers.get(name);
        if (handler == null) {
            peer.reply(id, error("No handler for " + name));
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                peer.reply(id, successful(handler.apply(copy.get("params"))));
            } catch (RuntimeException failure) {
                peer.reply(id, error(failure.getMessage()));
            }
        });
    }

    private static Map<String, Object> successful(Object result) {
        var body = new LinkedHashMap<String, Object>();
        body.put("result", result == null ? Map.of() : result);
        return body;
    }

    private static Map<String, Object> error(String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", Map.of("code", -32603, "message", message == null ? "failed" : message));
        return body;
    }

    private static RuntimeException asRuntime(Throwable error) {
        return error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
    }
}
