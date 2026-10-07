package com.pijava.mcp.transport.http;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.AuthProvider;
import com.pijava.mcp.oauth.McpOAuthAuthorizationRequiredError;
import com.pijava.mcp.oauth.McpOAuthProvider;
import com.pijava.mcp.oauth.McpOAuthProviderOptions;
import com.pijava.mcp.oauth.MemoryOAuthStateStore;
import com.pijava.mcp.oauth.OAuthClientMetadata;
import com.pijava.mcp.oauth.OAuthProviders;
import com.pijava.mcp.oauth.OAuthTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seam between the streamable transport and the OAuth flow: a 401 runs the
 * flow through the {@code Context.fetch} bridge and retries with the fresh
 * token (pi oauth.test.ts clauses 1-3).
 */
class OAuthTransportBridgeTest {

    private static final String MESSAGE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";

    @Test
    void refreshesOn401ThroughTheContextFetch() throws Exception {
        var server = ScriptedHttpServer.start(exchange -> authorize(exchange, "Bearer a2"));
        try {
            var provider = providerFor(server);
            provider.saveTokens(new OAuthTokens("a1", "Bearer", null, "org:read", "r1", null));
            var transport = transport(server, OAuthProviders.adaptOAuthProvider(provider));
            transport.start();

            transport.send(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));

            var calls = callsTo(server, "/mcp");
            assertThat(calls).hasSize(2);
            assertThat(calls.get(0)).isEqualTo("Bearer a1");
            assertThat(calls.get(1)).isEqualTo("Bearer a2");
            assertThat(provider.tokens().accessToken()).isEqualTo("a2");
            assertThat(provider.tokens().refreshToken()).isEqualTo("r2");
            transport.close();
        } finally {
            server.stop();
        }
    }

    @Test
    void insufficientScopeAsksForAuthorizationAndKeepsTheWorkingGrant() throws Exception {
        var server = ScriptedHttpServer.start(exchange -> {
            if ("/mcp".equals(path(exchange))) {
                ScriptedHttpServer.json(exchange, 403, "{\"error\":\"forbidden\"}",
                        Map.of("WWW-Authenticate",
                                "Bearer error=\"insufficient_scope\", scope=\"admin\""));
                return;
            }
            authorize(exchange, "Bearer never");
        });
        try {
            var provider = providerFor(server);
            provider.saveTokens(new OAuthTokens("a1", "Bearer", null, "repo read:org", "r1", null));
            var transport = transport(server, OAuthProviders.adaptOAuthProvider(provider));
            transport.start();

            assertThatThrownBy(() -> transport.send(
                    Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list")))
                    .isInstanceOf(McpOAuthAuthorizationRequiredError.class);
            // The challenge may list only the missing scopes; the new grant keeps the old ones.
            assertThat(provider.discoveryState()).isNotNull();
            assertThat(provider.tokens().accessToken()).isEqualTo("a1");
            assertThat(provider.tokens().scope()).isEqualTo("repo read:org");
            // No token request happened: a step-up goes straight to the redirect.
            assertThat(callsTo(server, "/token")).isEmpty();
            transport.close();
        } finally {
            server.stop();
        }
    }

    /** Serve discovery, token refresh, and an authenticated MCP endpoint. */
    private static void authorize(com.sun.net.httpserver.HttpExchange exchange,
                                  String accepted) throws Exception {
        var path = path(exchange);
        if ("/mcp".equals(path)) {
            var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!accepted.equals(authorization)) {
                ScriptedHttpServer.json(exchange, 401, "Unauthorized",
                        Map.of("WWW-Authenticate", "Bearer"));
                return;
            }
            ScriptedHttpServer.json(exchange, 200, MESSAGE, Map.of());
            return;
        }
        if ("/token".equals(path)) {
            ScriptedHttpServer.json(exchange, 200,
                    "{\"access_token\":\"a2\",\"refresh_token\":\"r2\","
                            + "\"token_type\":\"Bearer\"}", Map.of());
            return;
        }
        if (path.endsWith("oauth-authorization-server")) {
            var origin = origin(exchange);
            ScriptedHttpServer.json(exchange, 200,
                    "{\"issuer\":\"" + origin + "\","
                            + "\"authorization_endpoint\":\"" + origin + "/authorize\","
                            + "\"token_endpoint\":\"" + origin + "/token\","
                            + "\"response_types_supported\":[\"code\"]}", Map.of());
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private StreamableHttpTransport transport(ScriptedHttpServer server, AuthProvider auth) {
        return new StreamableHttpTransport(new StreamableHttpTransportOptions(
                server.url() + "/mcp", null, null, false, 0, auth, null));
    }

    private static McpOAuthProvider providerFor(ScriptedHttpServer server) {
        return new McpOAuthProvider(new McpOAuthProviderOptions(
                URI.create(server.url() + "/mcp"),
                URI.create("http://127.0.0.1/callback"),
                new OAuthClientMetadata(null, null, null, null, "test-client",
                        null, null, null, null, null, null, null, null, null, null, null),
                null, "client", null, new MemoryOAuthStateStore(), url -> { }));
    }

    private static String path(com.sun.net.httpserver.HttpExchange exchange) {
        return exchange.getRequestURI().getRawPath();
    }

    private static String origin(com.sun.net.httpserver.HttpExchange exchange) {
        return "http://" + exchange.getLocalAddress().getHostString()
                + ":" + exchange.getLocalAddress().getPort();
    }

    private static List<String> callsTo(ScriptedHttpServer server, String path) {
        return server.received().stream()
                .filter(item -> item.path().equals(path))
                .map(OAuthTransportBridgeTest::authorizationOf)
                .toList();
    }

    private static String authorizationOf(ScriptedHttpServer.Received item) {
        for (var entry : item.headers().entrySet()) {
            if ("authorization".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "";
    }
}
