package com.pijava.ai.auth;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RadiusOAuthFlowTest {

    @Test
    void deviceCodeUsesRadiusPayloadAndSkewsExpiry() throws Exception {
        try (var server = new FakeRadiusOAuthServer()) {
            server.enqueueDevice(60, 1);
            server.enqueueToken("access", "refresh", 3600);
            var flow = new RadiusOAuthFlow(
                new RadiusOAuthConfig("Radius", server.gateway()), server.httpClient(), 0);
            var before = Instant.now().getEpochSecond();

            var credential = flow.login(new ScriptedOAuthInteraction("device-code", server));

            assertThat(server.deviceForm).contains("client_id=pi-gateway")
                .contains("scope=gateway+offline_access");
            assertThat(credential.accessToken()).isEqualTo("access");
            assertThat(credential.refreshToken()).isEqualTo("refresh");
            assertThat(credential.expiresAtEpochSec()).isBetween(before + 3538, before + 3542);
            assertThat(server.authorizeQuery).isNull();
        }
    }

    @Test
    void browserUsesPkceStateHandoffAndCallback() throws Exception {
        try (var server = new FakeRadiusOAuthServer()) {
            server.enqueueDiscovery();
            server.enqueueToken("browser-access", "browser-refresh", 3600);
            var flow = new RadiusOAuthFlow(
                new RadiusOAuthConfig("Radius", server.gateway()), server.httpClient(), 0);

            var credential = flow.login(new ScriptedOAuthInteraction("browser", server));

            assertThat(server.authorizeQuery).containsEntry("code_challenge_method", "S256")
                .containsEntry("handoff", "url").containsKey("state");
            assertThat(server.tokenForm).contains("grant_type=authorization_code")
                .contains("code_verifier=");
            assertThat(credential.accessToken()).isEqualTo("browser-access");
        }
    }

    @Test
    void refreshUsesRadiusTokenEndpointAndForm() throws Exception {
        try (var server = new FakeRadiusOAuthServer()) {
            server.enqueueToken("new-access", "new-refresh", 3600);
            var flow = new RadiusOAuthFlow(
                new RadiusOAuthConfig("Radius", server.gateway()), server.httpClient(), 0);

            var credential = flow.refresh(new OAuthCredential("old", "old-refresh", 0, null));

            assertThat(server.tokenForm).contains("grant_type=refresh_token")
                .contains("refresh_token=old-refresh").contains("client_id=pi-gateway");
            assertThat(credential.accessToken()).isEqualTo("new-access");
            assertThat(credential.refreshToken()).isEqualTo("new-refresh");
        }
    }

    /** Device/browser interaction with notification-driven local callback. */
    private static final class ScriptedOAuthInteraction implements OAuthInteraction {
        private final String method;
        private final FakeRadiusOAuthServer server;

        private ScriptedOAuthInteraction(String method, FakeRadiusOAuthServer server) {
            this.method = method;
            this.server = server;
        }

        @Override
        public void notify(String message) {
            if (message.contains("/authorize?")) {
                var start = message.indexOf("http");
                server.openAuthorization(message.substring(start).trim());
            }
        }

        @Override
        public String prompt(String message) {
            return "";
        }

        @Override
        public String select(String message, java.util.List<LoginOption> options) {
            return method;
        }
    }

    /** Complete fake gateway; no external network or real browser is involved. */
    private static final class FakeRadiusOAuthServer implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final HttpClient client = HttpClient.newHttpClient();
        private String deviceBody;
        private String tokenBody;
        private boolean discovery;
        private volatile String deviceForm;
        private volatile String tokenForm;
        private volatile Map<String, String> authorizeQuery;

        private FakeRadiusOAuthServer() throws Exception {
            server.createContext("/v1/oauth/device", exchange -> {
                deviceForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(exchange, 200, deviceBody);
            });
            server.createContext("/v1/oauth/token", exchange -> {
                tokenForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(exchange, 200, tokenBody);
            });
            server.createContext("/v1/oauth", exchange -> respond(exchange, 200,
                "{\"authorizationEndpoint\":\"" + gateway() + "/authorize\"}"));
            server.createContext("/authorize", exchange -> {
                authorizeQuery = query(exchange.getRequestURI().getRawQuery());
                var redirect = authorizeQuery.get("redirect_uri");
                var state = authorizeQuery.get("state");
                client.sendAsync(HttpRequest.newBuilder(URI.create(redirect + "?code=test-code&state=" + state))
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding());
                respond(exchange, 200, "ok");
            });
            server.start();
        }

        private void enqueueDevice(long expiresIn, long interval) {
            deviceBody = "{\"device_code\":\"dc\",\"user_code\":\"user-code\","
                + "\"verification_uri\":\"https://verify.example.com\",\"expires_in\":"
                + expiresIn + ",\"interval\":" + interval + "}";
        }

        private void enqueueToken(String access, String refresh, long expiresIn) {
            tokenBody = "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh
                + "\",\"expires_in\":" + expiresIn + ",\"scope\":\"gateway offline_access\"}";
        }

        private void enqueueDiscovery() {
            discovery = true;
        }

        private void openAuthorization(String url) {
            client.sendAsync(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        }

        private String gateway() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private HttpClient httpClient() {
            return client;
        }

        private static Map<String, String> query(String raw) {
            var out = new HashMap<String, String>();
            for (var pair : raw.split("&")) {
                var parts = pair.split("=", 2);
                out.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            return out;
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                    int status, String body) throws java.io.IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
