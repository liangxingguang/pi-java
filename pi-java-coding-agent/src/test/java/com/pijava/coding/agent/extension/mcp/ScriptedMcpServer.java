package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.pijava.mcp.protocol.McpVersion;
import com.pijava.mcp.runtime.McpTransportFactory;
import com.pijava.mcp.transport.AbstractMcpTransport;

/**
 * 批 24（docs/35 §6）：脚本化 MCP 服务器夹具（pi-java-mcp 测试源集
 * ScriptedServer/ScriptedTransport 的自包含翻版——跨模块不可见，遂复制）。
 * 按方法名答 JSON-RPC；{@code hold} 可扣住应答（就绪等待/超时臂用）。
 */
final class ScriptedMcpServer {

    private final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Object, Object>> handlers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();
    private final List<ScriptedTransport> peers = new CopyOnWriteArrayList<>();
    private final List<String> toolNames;
    private final boolean withResources;

    private ScriptedMcpServer(List<String> toolNames, boolean withResources) {
        this.toolNames = toolNames;
        this.withResources = withResources;
    }

    static ScriptedMcpServer withTools(String... names) {
        return new ScriptedMcpServer(List.of(names), false);
    }

    static ScriptedMcpServer withToolsAndResources(String... names) {
        return new ScriptedMcpServer(List.of(names), true);
    }

    /** 传输工厂（McpExtension.Overrides.transportFactory 用）。 */
    McpTransportFactory factory() {
        return (entry, cwd, authProvider) -> connect();
    }

    /** 客户发来的全部线消息。 */
    List<Map<String, Object>> sent() {
        return new ArrayList<>(sent);
    }

    /** 扣住某方法的应答；返回的 future 完成即放行。 */
    CompletableFuture<Void> hold(String method) {
        var gate = new CompletableFuture<Void>();
        gates.put(method, gate);
        return gate;
    }

    /** 应答 {@code method} 用 {@code handler}（入参 params，出参 result 体）。 */
    void answer(String method, Function<Object, Object> handler) {
        handlers.put(method, handler);
    }

    int transportCount() {
        return peers.size();
    }

    boolean allClosed() {
        return peers.stream().allMatch(ScriptedTransport::isClosed);
    }

    private ScriptedTransport connect() {
        return new ScriptedTransport(this);
    }

    private void onSend(Map<String, Object> message, ScriptedTransport transport) {
        sent.add(message);
        var id = message.get("id");
        if (id == null) {
            return; // 通知（initialized 等）
        }
        var method = String.valueOf(message.get("method"));
        var gate = gates.remove(method);
        // 门控在另一虚拟线程等——send 的调用线程（client 请求线程）不能被门卡死
        CompletableFuture.runAsync(() -> {
            if (gate != null) {
                gate.join();
            }
            transport.reply(id, answerFor(method, message.get("params")));
        }, Thread::startVirtualThread).join();
    }

    private Map<String, Object> answerFor(String method, Object params) {
        var handler = handlers.get(method);
        if (handler != null) {
            return result(handler.apply(params));
        }
        return switch (method) {
            case "initialize" -> result(initializeResult());
            case "tools/list" -> result(Map.of("tools", toolsPayload()));
            case "resources/list" -> result(Map.of("resources", resourcesPayload()));
            case "resources/templates/list" -> result(Map.of("resourceTemplates", List.of()));
            case "ping" -> result(Map.of());
            default -> error(-32601, "Method not found: " + method);
        };
    }

    private List<Map<String, Object>> toolsPayload() {
        var tools = new ArrayList<Map<String, Object>>();
        for (var name : toolNames) {
            var tool = new LinkedHashMap<String, Object>();
            tool.put("name", name);
            tool.put("description", "Scripted tool " + name);
            tool.put("inputSchema", Map.of("type", "object"));
            tools.add(tool);
        }
        return tools;
    }

    private List<Map<String, Object>> resourcesPayload() {
        if (!withResources) {
            return List.of();
        }
        var resource = new LinkedHashMap<String, Object>();
        resource.put("uri", "scripted://readme");
        resource.put("name", "readme");
        return List.of(resource);
    }

    private Map<String, Object> initializeResult() {
        var capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("tools", Map.of("listChanged", true));
        if (withResources) {
            capabilities.put("resources", Map.of("listChanged", true));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", McpVersion.LATEST);
        result.put("capabilities", capabilities);
        result.put("serverInfo", Map.of("name", "scripted", "version", "1.0"));
        result.put("instructions", "Scripted server instructions.");
        return result;
    }

    private static Map<String, Object> result(Object value) {
        return Map.of("result", value);
    }

    private static Map<String, Object> error(int code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }

    /** 一条连接（重连得新实例，同真传输厂）。 */
    static final class ScriptedTransport extends AbstractMcpTransport {
        private final ScriptedMcpServer server;
        private volatile boolean closed;

        private ScriptedTransport(ScriptedMcpServer server) {
            this.server = server;
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public void start() {
        }

        @Override
        public void send(Map<String, Object> message) {
            if (closed) {
                throw new IllegalStateException("transport closed");
            }
            server.onSend(message, this);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                emitClose();
            }
        }

        void reply(Object id, Map<String, Object> body) {
            var message = new LinkedHashMap<String, Object>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.putAll(body);
            emitMessage(message);
        }
    }
}
