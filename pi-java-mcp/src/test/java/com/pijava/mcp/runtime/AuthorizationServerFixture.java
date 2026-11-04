package com.pijava.mcp.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.pijava.mcp.McpJson;

/**
 * A real loopback authorization server for the end-to-end sign-in test
 * (pi {@code test/oauth.test.ts:96-280}): protected-resource and
 * authorization-server metadata, dynamic client registration, an
 * authorization endpoint that redirects like a signed-in user, a token
 * endpoint that verifies PKCE and issued refresh tokens, and the MCP endpoint
 * the resulting access token opens.
 */
final class AuthorizationServerFixture implements AutoCloseable {

    /** The deliberate deviation that makes the sign-in fail. */
    enum Mode {

        /** Everything follows the specification. */
        NORMAL,

        /** The authorization endpoint answers with a state from another sign-in. */
        WRONG_STATE,

        /** The authorization response names another issuer (RFC 9207). */
        WRONG_ISSUER,

        /** The token endpoint expects a challenge that this verifier does not produce. */
        WRONG_VERIFIER
    }

    private static final String CODE = "test-code";

    private final HttpServer server;
    private final Mode mode;
    private final AtomicReference<Map<String, String>> authorization =
            new AtomicReference<>(Map.of());
    private final AtomicReference<@Nullable String> challenge = new AtomicReference<>();
    private final AtomicInteger registrations = new AtomicInteger();
    private final AtomicInteger exchanges = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger();

    private AuthorizationServerFixture(HttpServer server, Mode mode) {
        this.server = server;
        this.mode = mode;
    }

    /** Start on a free loopback port. */
    static AuthorizationServerFixture start(Mode mode) throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var fixture = new AuthorizationServerFixture(http, mode);
        http.createContext("/", fixture::dispatch);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return fixture;
    }

    /** The authorization server's origin, also its issuer. */
    String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The protected MCP endpoint. */
    String mcpUrl() {
        return origin() + "/mcp";
    }

    /** The parameters of the authorization request, decoded. */
    Map<String, String> authorizationQuery() {
        return authorization.get();
    }

    /** How many dynamic client registrations arrived. */
    int registrations() {
        return registrations.get();
    }

    /** How many authorization code exchanges arrived. */
    int exchanges() {
        return exchanges.get();
    }

    /** How many refresh-token exchanges arrived. */
    int refreshes() {
        return refreshes.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/.well-known/oauth-protected-resource/mcp" -> json(exchange, 200, Map.of(
                    "resource", mcpUrl(),
                    "authorization_servers", List.of(origin()),
                    "scopes_supported", List.of("org:read")));
            case "/.well-known/oauth-authorization-server" -> json(exchange, 200, metadata());
            case "/register" -> register(exchange);
            case "/authorize" -> authorize(exchange);
            case "/token" -> token(exchange);
            case "/mcp" -> mcp(exchange);
            default -> exchange.sendResponseHeaders(404, -1);
        }
    }

    private Map<String, Object> metadata() {
        var map = new LinkedHashMap<String, Object>();
        map.put("issuer", origin());
        map.put("authorization_endpoint", origin() + "/authorize");
        map.put("token_endpoint", origin() + "/token");
        map.put("registration_endpoint", origin() + "/register");
        map.put("response_types_supported", List.of("code"));
        map.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        map.put("token_endpoint_auth_methods_supported", List.of("none"));
        map.put("code_challenge_methods_supported", List.of("S256"));
        map.put("authorization_response_iss_parameter_supported", true);
        return map;
    }

    /** Dynamic client registration: echo the metadata and assign the test client. */
    private void register(HttpExchange exchange) throws IOException {
        registrations.incrementAndGet();
        var request = McpJson.mapper().readTree(exchange.getRequestBody().readAllBytes());
        var response = McpJson.mapper().createObjectNode();
        if (request instanceof ObjectNode object) {
            response.setAll(object);
        }
        response.put("client_id", "test-client");
        // An empty secret counts as absent (#10266).
        response.put("client_secret", "");
        json(exchange, 201, response);
    }

    /** Record the PKCE challenge and answer as a user who approved the request. */
    private void authorize(HttpExchange exchange) throws IOException {
        var query = parseForm(exchange.getRequestURI().getRawQuery());
        authorization.set(Map.copyOf(query));
        challenge.set(query.get("code_challenge"));
        var state = mode == Mode.WRONG_STATE ? "wrong-state" : query.getOrDefault("state", "");
        // The metadata promises the `iss` parameter, so every response carries it.
        var issuer = mode == Mode.WRONG_ISSUER ? "https://wrong-issuer.test" : origin();
        var location = new StringBuilder(query.getOrDefault("redirect_uri", ""))
                .append("?code=").append(CODE)
                .append("&state=").append(encode(state))
                .append("&iss=").append(encode(issuer));
        exchange.getResponseHeaders().add("Location", location.toString());
        exchange.sendResponseHeaders(302, -1);
    }

    /** Refresh tokens verbatim; exchange codes only when the PKCE verifier matches. */
    private void token(HttpExchange exchange) throws IOException {
        var form = parseForm(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        if ("refresh_token".equals(form.get("grant_type"))) {
            refreshes.incrementAndGet();
            json(exchange, 200, Map.of(
                    "access_token", "refreshed-token",
                    "token_type", "Bearer",
                    // Without a refresh token the client keeps the one it used.
                    "refresh_token", ""));
            return;
        }
        exchanges.incrementAndGet();
        var verifier = form.getOrDefault("code_verifier", "");
        var expected = challengeOf(mode == Mode.WRONG_VERIFIER ? verifier + "-wrong" : verifier);
        if (!CODE.equals(form.get("code")) || !expected.equals(challenge.get())) {
            json(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        json(exchange, 200, Map.of(
                "access_token", "first-token",
                "token_type", "Bearer",
                "refresh_token", "refresh-token"));
    }

    /** The MCP endpoint: unauthorized without the issued access token. */
    private void mcp(HttpExchange exchange) throws IOException {
        var method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        if ("DELETE".equals(method)) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var token = exchange.getRequestHeaders().getFirst("Authorization");
        if (!"Bearer first-token".equals(token) && !"Bearer refreshed-token".equals(token)) {
            exchange.getResponseHeaders().add("WWW-Authenticate",
                    "Bearer resource_metadata=\"" + origin()
                            + "/.well-known/oauth-protected-resource/mcp\", scope=\"\"");
            exchange.sendResponseHeaders(401, -1);
            return;
        }
        var message = McpJson.mapper().readTree(body);
        var id = message.get("id");
        if (id == null) {
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        var result = "initialize".equals(message.path("method").asText())
                ? Map.of("protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "test-authorization-server", "version", "1.0.0"))
                : Map.of("tools", List.of(Map.of("name", "issues",
                        "inputSchema", Map.of("type", "object"))));
        var response = McpJson.mapper().createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", McpJson.mapper().valueToTree(result));
        json(exchange, 200, response);
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        var bytes = McpJson.mapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Parse an {@code application/x-www-form-urlencoded} string. */
    static Map<String, String> parseForm(@Nullable String encoded) {
        var form = new LinkedHashMap<String, String>();
        if (encoded == null || encoded.isEmpty()) {
            return form;
        }
        for (var pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            var separator = pair.indexOf('=');
            var key = separator < 0 ? pair : pair.substring(0, separator);
            var value = separator < 0 ? "" : pair.substring(separator + 1);
            form.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return form;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String challengeOf(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
