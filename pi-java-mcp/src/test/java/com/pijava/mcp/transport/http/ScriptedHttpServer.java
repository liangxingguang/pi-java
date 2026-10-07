package com.pijava.mcp.transport.http;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Scriptable loopback HTTP server for transport tests: one responder handles
 * every request; received request lines (method, path, selected headers) are
 * recorded in order.
 */
final class ScriptedHttpServer {

    /** Handles one exchange. */
    @FunctionalInterface
    interface Responder {

        /** Handle. */
        void handle(HttpExchange exchange) throws Exception;
    }

    /** One recorded request. */
    record Received(String method, String path, Map<String, String> headers) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Responder responder;

    private ScriptedHttpServer(HttpServer server) {
        this.server = server;
    }

    /** Start a server with the given responder. */
    static ScriptedHttpServer start(Responder responder) throws Exception {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var scripted = new ScriptedHttpServer(http);
        scripted.responder = responder;
        http.createContext("/", scripted::dispatch);
        http.start();
        return scripted;
    }

    /** Base URL. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Recorded requests, in order. */
    List<Received> received() {
        return received;
    }

    /** Replace the responder. */
    void setResponder(Responder next) {
        responder = next;
    }

    /** Stop accepting. */
    void stop() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) {
        var headers = new java.util.LinkedHashMap<String, String>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        received.add(new Received(exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(), headers));
        try {
            responder.handle(exchange);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    /** Send a plain JSON response. */
    static void json(HttpExchange exchange, int status, String body,
                     Map<String, String> headers) throws Exception {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Begin a chunked SSE response; the caller writes frames and closes. */
    static OutputStream beginSse(HttpExchange exchange,
                                 Map<String, String> headers) throws Exception {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(200, 0);
        return exchange.getResponseBody();
    }

    /** Empty acknowledgement (202/204). */
    static void acknowledgement(HttpExchange exchange, int status) throws Exception {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
