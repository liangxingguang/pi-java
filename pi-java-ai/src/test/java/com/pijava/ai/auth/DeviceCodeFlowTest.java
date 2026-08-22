package com.pijava.ai.auth;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P6: DeviceCodeFlow — RFC 8628 device-code 登录（pending/slow_down/两段式）与 refresh。
 */
class DeviceCodeFlowTest {

    private static final String TOKEN_JSON = """
        {"access_token":"dev-access","refresh_token":"dev-refresh","expires_in":3600}
        """;

    private static final String DEVICE_JSON =
        "{\"device_code\":\"dc\",\"user_code\":\"1234\",\"verification_uri\":\"https://verify.example.com\""
            + ",\"interval\":1,\"expires_in\":300}";

    @Test
    void loginPollsUntilAuthorizationCompletes() throws Exception {
        var deviceServer = startServer("/device", req -> new Resp(200, DEVICE_JSON));
        var calls = new AtomicInteger();
        var tokenServer = startServer("/token", req -> calls.incrementAndGet() == 1
            ? new Resp(400, "{\"error\":\"authorization_pending\"}")
            : new Resp(200, TOKEN_JSON));

        var config = DeviceCodeConfig.standard("test",
            url(deviceServer, "/device"), url(tokenServer, "/token"), "client-1", "scope-a");
        var credential = new DeviceCodeFlow(config, false).login(notifyOnly());

        assertThat(credential.accessToken()).isEqualTo("dev-access");
        assertThat(credential.refreshToken()).isEqualTo("dev-refresh");
        assertThat(credential.isExpired()).isFalse();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void loginHandlesSlowDownBeforeSuccess() throws Exception {
        var deviceServer = startServer("/device", req -> new Resp(200, DEVICE_JSON));
        var calls = new AtomicInteger();
        var tokenServer = startServer("/token", req -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return new Resp(400, "{\"error\":\"slow_down\"}");
            }
            return new Resp(200, TOKEN_JSON);
        });

        var config = DeviceCodeConfig.standard("test",
            url(deviceServer, "/device"), url(tokenServer, "/token"), "client-1", "");
        var credential = new DeviceCodeFlow(config, false).login(notifyOnly());

        assertThat(credential.accessToken()).isEqualTo("dev-access");
    }

    @Test
    void deniedAuthorizationThrows() throws Exception {
        var deviceServer = startServer("/device", req -> new Resp(200, DEVICE_JSON));
        var tokenServer = startServer("/token", req -> new Resp(400, "{\"error\":\"access_denied\"}"));

        var config = DeviceCodeConfig.standard("test",
            url(deviceServer, "/device"), url(tokenServer, "/token"), "client-1", "");
        var thrown = catchThrowable(() -> new DeviceCodeFlow(config, false).login(notifyOnly()));

        assertThat(thrown).isInstanceOf(Exception.class).hasMessageContaining("denied");
    }

    @Test
    void refreshExchangesRefreshToken() throws Exception {
        var tokenServer = startServer("/token", req -> new Resp(200, TOKEN_JSON));
        var config = DeviceCodeConfig.standard("test",
            "https://verify.example.com/device", url(tokenServer, "/token"), "client-1", "");

        var refreshed = new DeviceCodeFlow(config, false)
            .refresh(new OAuthCredential("old", "rt", 0, null));

        assertThat(refreshed.accessToken()).isEqualTo("dev-access");
    }

    @Test
    void refreshWithoutRefreshTokenThrows() {
        var config = DeviceCodeConfig.standard("test",
            "https://verify.example.com/device", "https://verify.example.com/token", "c", "");
        var thrown = catchThrowable(() -> new DeviceCodeFlow(config, false)
            .refresh(new OAuthCredential("access", "", 0, null)));

        assertThat(thrown).isInstanceOf(Exception.class)
            .hasMessageContaining("No refresh token");
    }

    @Test
    void openAiCodexTwoStageExchange() throws Exception {
        var deviceServer = startServer("/usercode", req ->
            new Resp(200, "{\"device_auth_id\":\"daid\",\"user_code\":\"9999\",\"interval\":1}"));
        var calls = new AtomicInteger();
        var pollServer = startServer("/poll", req -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return new Resp(403, "{}"); // pending
            }
            return new Resp(200, "{\"authorization_code\":\"ac\",\"code_verifier\":\"cv\"}");
        });
        var exchangeServer = startServer("/exchange", req -> new Resp(200, TOKEN_JSON));

        var config = DeviceCodeConfig.codex("test",
            url(deviceServer, "/usercode"), url(pollServer, "/poll"), "codex-client",
            "", "https://auth.example.com/codex/device",
            url(exchangeServer, "/exchange"), "https://auth.example.com/deviceauth/callback");
        var credential = new DeviceCodeFlow(config, false).login(notifyOnly());

        assertThat(credential.accessToken()).isEqualTo("dev-access");
        assertThat(calls.get()).isEqualTo(2);
    }

    // ── Helpers ──────────────────────────────────────────────

    private static OAuthInteraction notifyOnly() {
        return new OAuthInteraction() {
            @Override public void notify(String message) { }
            @Override public String prompt(String message) { return ""; }
        };
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static HttpServer startServer(String path, Handler handler) throws Exception {
        var server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, exchange -> {
            var requestBody = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var resp = handler.handle(requestBody);
            var bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(resp.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private record Resp(int status, String body) {
    }

    private interface Handler {
        Resp handle(String requestBody);
    }
}
