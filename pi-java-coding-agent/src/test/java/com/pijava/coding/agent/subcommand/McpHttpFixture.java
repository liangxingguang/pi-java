package com.pijava.coding.agent.subcommand;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.pijava.mcp.McpJson;

/**
 * An MCP server over streamable HTTP that requires a bearer token, for
 * {@code mcp list} tests.
 */
final class McpHttpFixture {

    private final HttpServer server;
    private final String token;

    private McpHttpFixture(HttpServer server, String token) {
        this.server = server;
        this.token = token;
    }

    /** Start on a free loopback port, accepting {@code Bearer <token>}. */
    static McpHttpFixture start(String token) throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var fixture = new McpHttpFixture(http, token);
        http.createContext("/mcp", fixture::handle);
        http.start();
        return fixture;
    }

    /** The server's endpoint. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    /** Stop accepting. */
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var authorized = ("Bearer " + token)
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"));
            if (!authorized) {
                // What the transport turns into a challenge, so the server needs a sign-in.
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                respond(exchange, 401, "{}");
                return;
            }
            var request = McpJson.mapper().readTree(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (request.path("id").isMissingNode()) {
                // The `notifications/initialized` acknowledgement.
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            respond(exchange, 200, reply(request));
        }
    }

    private static String reply(com.fasterxml.jackson.databind.JsonNode request) {
        var result = switch (request.path("method").asText()) {
            case "initialize" -> "{\"protocolVersion\":\"2025-11-25\","
                    + "\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"secure\",\"version\":\"1\"}}";
            case "tools/list" -> "{\"tools\":[{\"name\":\"echo\","
                    + "\"inputSchema\":{\"type\":\"object\"}}]}";
            default -> "{}";
        };
        return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id") + ",\"result\":" + result + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
