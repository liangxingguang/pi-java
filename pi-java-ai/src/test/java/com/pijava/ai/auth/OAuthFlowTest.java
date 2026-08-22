package com.pijava.ai.auth;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P6-17: OAuthFlow — PKCE 授权码流程（loopback 回调 + token 换取）与 refresh。
 */
class OAuthFlowTest {

    private static final String TOKEN_JSON = """
        {"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600}
        """;

    @Test
    void loginExchangesAuthorizationCode() throws Exception {
        var tokenServer = startTokenServer(response -> TOKEN_JSON);
        var config = OAuthConfig.standard("test",
            "https://auth.example.com/authorize",
            "http://127.0.0.1:" + port(tokenServer) + "/token",
            "client-1", "scope-a");
        var flow = new OAuthFlow(config, false);
        var callbackUrl = new AtomicReference<String>();

        var credential = flow.login(interaction(callbackUrl));

        assertThat(credential.accessToken()).isEqualTo("test-access");
        assertThat(credential.refreshToken()).isEqualTo("test-refresh");
        assertThat(credential.isExpired()).isFalse();
        assertThat(callbackUrl.get()).contains("/oauth/callback/");
    }

    @Test
    void loginAcceptsManualCodePaste() throws Exception {
        var tokenServer = startTokenServer(response -> TOKEN_JSON);
        var config = OAuthConfig.standard("test",
            "https://auth.example.com/authorize",
            "http://127.0.0.1:" + port(tokenServer) + "/token",
            "", "");
        var flow = new OAuthFlow(config, false);
        var interaction = new OAuthInteraction() {
            @Override public void notify(String message) { }
            @Override public String prompt(String message) { return "some-code"; }
        };

        var credential = flow.login(interaction);

        assertThat(credential.accessToken()).isEqualTo("test-access");
    }

    @Test
    void refreshExchangesRefreshToken() throws Exception {
        var tokenServer = startTokenServer(response -> TOKEN_JSON);
        var config = OAuthConfig.standard("test",
            "https://auth.example.com/authorize",
            "http://127.0.0.1:" + port(tokenServer) + "/token",
            "client-1", "");
        var flow = new OAuthFlow(config, false);

        var refreshed = flow.refresh(new OAuthCredential("old", "rt", 0, null));

        assertThat(refreshed.accessToken()).isEqualTo("test-access");
    }

    @Test
    void refreshWithoutRefreshTokenThrows() {
        var config = OAuthConfig.standard("test", "https://auth.example.com/authorize",
            "https://auth.example.com/token", "c", "");
        var flow = new OAuthFlow(config, false);

        var thrown = catchThrowable(() -> flow.refresh(
            new OAuthCredential("access", "", 0, null)));

        assertThat(thrown).isInstanceOf(Exception.class)
            .hasMessageContaining("No refresh token");
    }

    @Test
    void tokenExchangeFailureThrows() throws Exception {
        var tokenServer = startTokenServer(response -> "{\"error\":\"invalid_grant\"}");
        var config = OAuthConfig.standard("test",
            "https://auth.example.com/authorize",
            "http://127.0.0.1:" + port(tokenServer) + "/token",
            "", "");
        var flow = new OAuthFlow(config, false);
        var callbackUrl = new AtomicReference<String>();

        var thrown = catchThrowable(() -> flow.login(interaction(callbackUrl)));

        assertThat(thrown).isInstanceOf(Exception.class);
    }

    // ── Helpers ──────────────────────────────────────────────

    private static OAuthInteraction interaction(AtomicReference<String> callbackUrl) {
        return new OAuthInteraction() {
            @Override public void notify(String message) {
                if (message.startsWith("Listening for OAuth callback on ")) {
                    callbackUrl.set(message.substring("Listening for OAuth callback on ".length()));
                }
            }

            @Override public String prompt(String message) {
                // 模拟浏览器回调：命中 loopback 服务器 ?code=...
                try (var client = HttpClient.newHttpClient()) {
                    client.send(HttpRequest.newBuilder(
                        URI.create(callbackUrl.get() + "?code=auth-code")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return "";
            }
        };
    }

    private static int port(HttpServer server) {
        return server.getAddress().getPort();
    }

    private static HttpServer startTokenServer(ResponseBody body) throws Exception {
        var server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> {
            var requestBody = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var payload = body.get(requestBody);
            var bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @FunctionalInterface
    private interface ResponseBody {
        String get(String requestBody);
    }
}
