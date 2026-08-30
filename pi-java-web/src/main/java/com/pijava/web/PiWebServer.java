package com.pijava.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.AgentSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.pijava.web.WebProtocol.WebClientMessage;
import com.pijava.web.WebProtocol.WebServerMessage;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * pi-java Web UI 服务器（Phase 7）—— 两个监听器同进程：
 *
 * <ul>
 *   <li>{@code com.sun.net.httpserver.HttpServer}：静态前端（classpath
 *       {@code /web/}）+ {@code /api/config}（返回 WS 端口）。</li>
 *   <li>{@link WebSocketServer}（Java-WebSocket）：{@code /api/ws}，JSON 协议
 *       {@link WebProtocol}。每连接一个 {@link AgentSession} + {@link WebDispatcher}。</li>
 * </ul>
 *
 * <p>前端 {@code getWsUrl()} 先取 {@code /api/config} 得 {@code wsPort} 再连 WS
 * （静态与 WS 不同端口，避免引入重 HTTP/WS 合并服务器依赖）。</p>
 */
public final class PiWebServer {

    private static final Logger LOG = LoggerFactory.getLogger(PiWebServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认监听端口。 */
    public static final int DEFAULT_PORT = 8787;

    private PiWebServer() {}

    /**
     * 启动 web UI 并阻塞直到被关闭（{@code --mode web} 入口）。
     *
     * @return 进程退出码（0 = 干净关闭）
     */
    public static int run(Args args) {
        int port = args.port() == null ? DEFAULT_PORT : args.port();
        int wsPort = port == DEFAULT_PORT ? DEFAULT_PORT + 1 : port + 1;
        // 首启自动生成 token 并落盘（默认受保护）；生成时打印一次供浏览器登录
        String token = GatewayToken.configured().orElseGet(() -> {
            String generated = GatewayToken.resolve();
            LOG.info("pi-java web UI gateway token generated: {} (saved to {})",
                generated, GatewayToken.DEFAULT_FILE);
            return generated;
        });
        try {
            return start(args, port, wsPort, token).await();
        } catch (Exception e) {
            LOG.error("Failed to start web UI", e);
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /** 运行句柄：{@code await()} 阻塞至关闭，{@code close()} 停止双服务器。 */
    static final class ServerHandle {
        private final CountDownLatch shutdown = new CountDownLatch(1);
        private final WebSocketServer ws;
        private final HttpServer http;

        ServerHandle(WebSocketServer ws, HttpServer http) {
            this.ws = ws;
            this.http = http;
        }

        /** 阻塞直到 {@link #close()} 被调用。 */
        int await() throws InterruptedException {
            shutdown.await();
            return 0;
        }

        void close() throws InterruptedException {
            shutdown.countDown();
            ws.stop(1000);
            http.stop(0);
        }
    }

    /** 启动双服务器（静态 + WS），token 取 {@link GatewayToken#resolve()}。 */
    static ServerHandle start(Args args, int port, int wsPort) throws IOException {
        return start(args, port, wsPort, GatewayToken.resolve());
    }

    /** 启动双服务器（静态 + WS）；{@code gatewayToken} 为空 = 不鉴权（测试用）。 */
    static ServerHandle start(Args args, int port, int wsPort, String gatewayToken)
            throws IOException {
        var http = HttpServer.create(new InetSocketAddress(port), 0);
        boolean requiresAuth = gatewayToken != null && !gatewayToken.isEmpty();
        http.createContext("/", exchange -> handleHttp(exchange, wsPort, requiresAuth));
        http.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        LOG.info("pi-java web UI serving static at http://localhost:{}/", port);
        if (requiresAuth) {
            LOG.info("pi-java web UI gateway auth enabled (token: {})", GatewayToken.DEFAULT_FILE);
        }

        var ws = new WebSocketServer(new InetSocketAddress(wsPort)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                if (requiresAuth && !authenticated(conn, gatewayToken)) {
                    LOG.warn("web auth rejected: {}", conn.getRemoteSocketAddress());
                    sendJson(conn, new WebServerMessage.Error(
                        "authentication required: connect /api/ws with ?token=..."));
                    conn.close(4401, "unauthorized");
                    return;
                }
                LOG.info("web client connected: {}", conn.getRemoteSocketAddress());
                AgentSession session;
                try {
                    session = AgentSession.createWeb(args);
                } catch (Exception e) {
                    LOG.error("Failed to create agent session for web client", e);
                    sendJson(conn, new WebServerMessage.Error(
                        "Failed to create agent session: " + e.getMessage()));
                    return;
                }
                var dispatcher = new WebDispatcher(session, args, msg -> sendJson(conn, msg));
                dispatcher.start();
                conn.setAttachment(dispatcher);
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                var dispatcher = (WebDispatcher) conn.getAttachment();
                if (dispatcher == null) {
                    return;
                }
                try {
                    WebClientMessage msg = JSON.readValue(message, WebClientMessage.class);
                    dispatcher.handle(msg);
                } catch (Exception e) {
                    LOG.warn("Invalid web message: {}", message, e);
                    sendJson(conn, new WebServerMessage.Error("Invalid message: " + e.getMessage()));
                }
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                var dispatcher = (WebDispatcher) conn.getAttachment();
                if (dispatcher != null) {
                    dispatcher.close();
                }
                LOG.info("web client disconnected: {}", conn.getRemoteSocketAddress());
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                LOG.warn("web socket error: {}", ex.getMessage());
            }

            @Override
            public void onStart() {
                LOG.info("pi-java web UI websocket listening at ws://localhost:{}/api/ws", wsPort);
            }
        };
        ws.start();
        return new ServerHandle(ws, http);
    }

    // ── HTTP 静态托管 ────────────────────────────────────────────────────

    private static void handleHttp(HttpExchange exchange, int wsPort, boolean requiresAuth)
            throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/api/config".equals(path)) {
                byte[] body = ("{\"wsPort\":" + wsPort + ",\"requiresAuth\":" + requiresAuth + "}")
                    .getBytes(StandardCharsets.UTF_8);
                respond(exchange, 200, "application/json", body);
                return;
            }
            if (path == null || path.isBlank() || "/".equals(path)) {
                path = "/index.html";
            }
            InputStream resource = PiWebServer.class.getResourceAsStream("/web" + path);
            if (resource == null) {
                // SPA 回退：未知路径返回 index.html
                resource = PiWebServer.class.getResourceAsStream("/web/index.html");
                if (resource == null) {
                    respond(exchange, 503, "text/plain; charset=utf-8",
                        ("Web UI frontend not found. Build pi-webui client into "
                            + "src/main/resources/web/ first.").getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            respond(exchange, 200, mimeType(path), resource.readAllBytes());
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType,
                                byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String mimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ── WS 网关鉴权 ─────────────────────────────────────────────────────

    /** 校验 WS 连接是否携带正确 token（URL 查询参数 {@code ?token=...}）。 */
    private static boolean authenticated(WebSocket conn, String expected) {
        return GatewayToken.matches(expected, queryParam(conn.getResourceDescriptor(), "token"));
    }

    /** 从 resource descriptor（如 {@code /api/ws?token=abc}）取查询参数。 */
    private static String queryParam(String descriptor, String name) {
        if (descriptor == null) {
            return null;
        }
        int q = descriptor.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : descriptor.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                String val = eq < 0 ? "" : pair.substring(eq + 1);
                return URLDecoder.decode(val, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void sendJson(WebSocket conn, WebServerMessage msg) {
        if (conn.isOpen()) {
            try {
                var json = JSON.writeValueAsString(msg);
                LOG.info("[ws->client] {}", truncate(json, 400));
                conn.send(json);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                LOG.warn("Failed to serialize web message", e);
            }
        }
    }

    /** 截断超长帧，避免日志刷屏（message_update 全文通常很长）。 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…(" + s.length() + " chars)";
    }
}
