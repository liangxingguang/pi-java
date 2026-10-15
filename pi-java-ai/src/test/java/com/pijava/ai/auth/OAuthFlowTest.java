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
 * 通用 PKCE 流（openrouter 形状）：loopback 回调走共享 {@link LoopbackCallbackServer}
 * （品牌页、complete 内交换）、手工粘贴竞速、refresh。
 */
class OAuthFlowTest {

    private static final String TOKEN_JSON = """
        {"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600}
        """;

    @Test
    void loginExchangesAuthorizationCodeThroughBrowserCallback() throws Exception {
        var exchangedCode = new AtomicReference<String>();
        var tokenServer = startTokenServer(requestBody -> {
            exchangedCode.set(formField(requestBody, "code"));
            return TOKEN_JSON;
        });
        var config = OAuthConfig.standard("test",
            "https://auth.example.com/authorize",
            "http://127.0.0.1:" + port(tokenServer) + "/token",
            "client-1", "scope-a");
        var flow = new OAuthFlow(config, false);
        var callbackUrl = new AtomicReference<String>();

        var credential = flow.login(browserInteraction(callbackUrl, "?code=auth-code"));

        assertThat(credential.accessToken()).isEqualTo("test-access");
        assertThat(credential.refreshToken()).isEqualTo("test-refresh");
        assertThat(credential.isExpired()).isFalse();
        assertThat(callbackUrl.get()).contains("/oauth/callback/");
        // 交换发生在回调 complete 内：桩记录到授权码（品牌页本身由 LoopbackCallbackServerTest 钉）。
        awaitCode(exchangedCode);
        assertThat(exchangedCode.get()).isEqualTo("auth-code");
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
    void exchangeFailureDuringCallbackShows502PageAndThrows() throws Exception {
        var tokenServer = startTokenServer(response -> {
            throw new ResponseFailure(503, "upstream down");
        });
        var config = OAuthConfig.standard("test",
            "https://auth.example.com/authorize",
            "http://127.0.0.1:" + port(tokenServer) + "/token",
            "", "");
        var flow = new OAuthFlow(config, false);
        var callbackUrl = new AtomicReference<String>();

        var thrown = catchThrowable(() ->
            flow.login(browserInteraction(callbackUrl, "?code=auth-code")));

        // complete 内交换失败 → 登录失败（502 页面由 LoopbackCallbackServerTest 钉）。
        assertThat(thrown).isInstanceOf(Exception.class)
            .hasMessageContaining("503");
    }

    private static void awaitCode(AtomicReference<String> code) throws InterruptedException {
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (code.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
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
    void refreshIsBoundedByFifteenSecondTimeout() throws Exception {
        // B231：挂起不响应的刷新端点；注入 1s 超时（生产值 15s 在 REFRESH_TIMEOUT 常量）。
        var tokenServer = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        tokenServer.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(30_000); // 永不响应（夹具 30s 后服务器随 JVM 退出）
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        tokenServer.start();
        var config = OAuthConfig.standard("test",
            "https://auth.example.com/authorize",
            "http://127.0.0.1:" + port(tokenServer) + "/token",
            "client-1", "");
        var flow = new OAuthFlow(config, false, java.time.Duration.ofSeconds(1));

        var started = System.nanoTime();
        var thrown = catchThrowable(() ->
            flow.refresh(new OAuthCredential("old", "rt", 0, null)));
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(thrown).isInstanceOf(java.io.IOException.class);
        assertThat(elapsedMs).isLessThan(10_000L);
    }

    // ── Helpers ──────────────────────────────────────────────

    /** prompt 被调用即模拟浏览器打回调 URL（discard 响应；页面行为在共享件夹具里钉）。 */
    private static OAuthInteraction browserInteraction(AtomicReference<String> callbackUrl,
                                                       String callbackQuery) {
        return new OAuthInteraction() {
            @Override public void notify(String message) {
                var marker = "OAuth callback on ";
                var idx = message.indexOf(marker);
                if (idx >= 0) {
                    callbackUrl.set(message.substring(idx + marker.length()).strip());
                }
            }

            @Override public String prompt(String message) {
                try (var client = HttpClient.newHttpClient()) {
                    client.send(HttpRequest.newBuilder(
                        URI.create(callbackUrl.get() + callbackQuery)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return "";
            }
        };
    }

    private static String formField(String form, String key) {
        for (var pair : form.split("&")) {
            var parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
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
            try {
                var payload = body.get(requestBody);
                var bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            } catch (ResponseFailure failure) {
                var bytes = failure.getMessage().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failure.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    /** 桩用：让 token 端点返回非 2xx。 */
    private static final class ResponseFailure extends RuntimeException {
        private final int status;
        ResponseFailure(int status, String message) {
            super(message);
            this.status = status;
        }
        int status() {
            return status;
        }
    }

    @FunctionalInterface
    private interface ResponseBody {
        String get(String requestBody);
    }
}
