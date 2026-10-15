package com.pijava.ai.auth;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 对应 pi {@code test/anthropic-oauth.test.ts}：select browser/copy-code、
 * 手工回调 URL、copy-code 固定 redirect、真回调、53692 占用回落、JSON 交换/刷新、到期 margin。
 */
class AnthropicOAuthFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN_JSON = """
        {"access_token":"access-token","refresh_token":"refresh-token","expires_in":3600}
        """;

    // ── browser 法：手工粘贴最终回调 URL ───────────────────────

    @Test
    void keepsLocalhostRedirectUriForManualCallbackLogin() throws Exception {
        var token = TokenStub.start(200, TOKEN_JSON);
        var flow = new AnthropicOAuthFlow(false, Duration.ofSeconds(15), "127.0.0.1", token.url());
        var authUrl = new AtomicReference<String>();

        var credential = flow.login(new ScriptedInteraction(authUrl)
            .selectAnswer("browser")
            .manual(message -> {
                var params = queryOf(authUrl.get());
                return params.get("redirect_uri") + "?code=manual-code&state=" + params.get("state");
            }));

        assertThat(credential.accessToken()).isEqualTo("access-token");
        var body = JSON.readTree(token.lastBody());
        assertThat(body.get("grant_type").asText()).isEqualTo("authorization_code");
        assertThat(body.get("code").asText()).isEqualTo("manual-code");
        assertThat(body.get("redirect_uri").asText()).isEqualTo("http://localhost:53692/callback");
        assertThat(body.get("state").asText()).isEqualTo(queryOf(authUrl.get()).get("state"));
        assertThat(body.get("code_verifier").asText()).isNotBlank();
        assertThat(body.has("scope")).isFalse();
        assertThat(token.requestCount()).isEqualTo(1);
    }

    // ── copy-code 法 ─────────────────────────────────────────

    @Test
    void copyCodeFlowUsesPlatformCallbackAndCodeHashState() throws Exception {
        var token = TokenStub.start(200, TOKEN_JSON);
        var flow = new AnthropicOAuthFlow(false, Duration.ofSeconds(15), "127.0.0.1", token.url());
        var authUrl = new AtomicReference<String>();
        var selectPrompts = new ArrayList<String>();

        var credential = flow.login(new ScriptedInteraction(authUrl)
            .selectAnswer("copy_code")
            .onSelect(selectPrompts::add)
            .manual(message -> "copied-code#" + queryOf(authUrl.get()).get("state")));

        assertThat(credential.accessToken()).isEqualTo("access-token");
        var params = queryOf(authUrl.get());
        assertThat(params.get("redirect_uri"))
            .isEqualTo("https://platform.claude.com/oauth/code/callback");
        assertThat(selectPrompts).containsExactly("Select Anthropic login method:");
        var body = JSON.readTree(token.lastBody());
        assertThat(body.get("code").asText()).isEqualTo("copied-code");
        assertThat(body.get("state").asText()).isEqualTo(params.get("state"));
        assertThat(body.get("redirect_uri").asText())
            .isEqualTo("https://platform.claude.com/oauth/code/callback");
    }

    @Test
    void copyCodeAlsoAcceptsPlainCodeAndFullUrlForms() throws Exception {
        var token = TokenStub.start(200, TOKEN_JSON);
        var flow = new AnthropicOAuthFlow(false, Duration.ofSeconds(15), "127.0.0.1", token.url());
        var authUrl = new AtomicReference<String>();

        flow.login(new ScriptedInteraction(authUrl).selectAnswer("copy_code").manual(message -> "just-the-code"));
        var body = JSON.readTree(token.lastBody());
        assertThat(body.get("code").asText()).isEqualTo("just-the-code");
        assertThat(body.get("state").asText()).isEqualTo(queryOf(authUrl.get()).get("state"));

        token.reset();
        var authUrl2 = new AtomicReference<String>();
        flow.login(new ScriptedInteraction(authUrl2).selectAnswer("copy_code")
            .manual(message -> queryOf(authUrl2.get()).get("redirect_uri")
                + "?code=url-code&state=" + queryOf(authUrl2.get()).get("state")));
        var body2 = JSON.readTree(token.lastBody());
        assertThat(body2.get("code").asText()).isEqualTo("url-code");
    }

    @Test
    void copyCodeStateMismatchThrows() throws Exception {
        var token = TokenStub.start(200, TOKEN_JSON);
        var flow = new AnthropicOAuthFlow(false, Duration.ofSeconds(15), "127.0.0.1", token.url());
        var authUrl = new AtomicReference<String>();

        var thrown = catchThrowable(() -> flow.login(new ScriptedInteraction(authUrl)
            .selectAnswer("copy_code").manual(message -> "code#wrong-state")));

        assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("state mismatch");
        assertThat(token.requestCount()).isZero();
    }

    @Test
    void selectionCancellationAndUnknownMethodPropagate() {
        var cancelled = catchThrowable(() -> new AnthropicOAuthFlow(false,
            Duration.ofSeconds(15), "127.0.0.1", "http://127.0.0.1/token")
            .login(new ScriptedInteraction(new AtomicReference<>()).selectFailure("Login cancelled")));
        assertThat(cancelled).hasMessageContaining("Login cancelled");

        var unknown = catchThrowable(() -> new AnthropicOAuthFlow(false,
            Duration.ofSeconds(15), "127.0.0.1", "http://127.0.0.1/token")
            .login(new ScriptedInteraction(new AtomicReference<>()).selectAnswer("telepathy")));
        assertThat(unknown).isInstanceOf(IOException.class)
            .hasMessageContaining("Unknown Anthropic login method: telepathy");
    }

    // ── 真浏览器回调 ─────────────────────────────────────────

    @Test
    void completesLoginThroughBrowserCallback() throws Exception {
        var token = TokenStub.start(200, TOKEN_JSON);
        var flow = new AnthropicOAuthFlow(false, Duration.ofSeconds(15), "127.0.0.1", token.url());
        var authUrl = new AtomicReference<String>();

        var credential = flow.login(new ScriptedInteraction(authUrl).selectAnswer("browser")
            .manual(message -> {
                var params = queryOf(authUrl.get());
                hitCallback(params.get("redirect_uri"),
                    "?code=browser-code&state=" + params.get("state"));
                return ""; // 浏览器回调赢
            }));

        assertThat(credential.accessToken()).isEqualTo("access-token");
        var body = JSON.readTree(token.lastBody());
        assertThat(body.get("code").asText()).isEqualTo("browser-code");
        assertThat(body.get("redirect_uri").asText()).startsWith("http://localhost:");
    }

    @Test
    void fallsBackToFreePortWhenPreferredPortIsTaken() throws Exception {
        var blocker = HttpServer.create(new InetSocketAddress("127.0.0.1", 53692), 0);
        blocker.start();
        try {
            var token = TokenStub.start(200, TOKEN_JSON);
            var flow = new AnthropicOAuthFlow(false, Duration.ofSeconds(15), "127.0.0.1", token.url());
            var authUrl = new AtomicReference<String>();

            flow.login(new ScriptedInteraction(authUrl).selectAnswer("browser")
                .manual(message -> {
                    var params = queryOf(authUrl.get());
                    hitCallback(params.get("redirect_uri"),
                        "?code=fallback-code&state=" + params.get("state"));
                    return "";
                }));

            var redirect = URI.create(queryOf(authUrl.get()).get("redirect_uri"));
            assertThat(redirect.getHost()).isEqualTo("localhost");
            assertThat(redirect.getPath()).isEqualTo("/callback");
            assertThat(redirect.getPort()).isNotEqualTo(53692);
            var body = JSON.readTree(token.lastBody());
            assertThat(body.get("code").asText()).isEqualTo("fallback-code");
            assertThat(body.get("redirect_uri").asText())
                .isEqualTo("http://localhost:" + redirect.getPort() + "/callback");
        } finally {
            blocker.stop(0);
        }
    }

    // ── 刷新（JSON、无 scope、5min margin） ───────────────────

    @Test
    void refreshPostsJsonWithoutScopeAndStoresFiveMinuteMargin() throws Exception {
        var token = TokenStub.start(200, """
            {"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}
            """);
        var flow = new AnthropicOAuthFlow(false, Duration.ofSeconds(15), "127.0.0.1", token.url());

        var before = epochNow();
        var credential = flow.refresh(new OAuthCredential("old-access", "refresh-token", 0, null));
        var after = epochNow();

        assertThat(credential.accessToken()).isEqualTo("new-access");
        assertThat(credential.refreshToken()).isEqualTo("new-refresh");
        assertThat(credential.expiresAtEpochSec()).isBetween(before + 3600 - 300, after + 3600 - 300);
        var body = JSON.readTree(token.lastBody());
        assertThat(body.get("grant_type").asText()).isEqualTo("refresh_token");
        assertThat(body.get("client_id").asText()).isEqualTo("9d1c250a-e61b-44d9-88ed-5944d1962f5e");
        assertThat(body.get("refresh_token").asText()).isEqualTo("refresh-token");
        assertThat(body.has("scope")).isFalse();
    }

    @Test
    void refreshFailureSurfacesHttpBody() throws Exception {
        var token = TokenStub.start(400, "{\"error\":\"invalid_grant\"}");
        var flow = new AnthropicOAuthFlow(false, Duration.ofSeconds(15), "127.0.0.1", token.url());

        var thrown = catchThrowable(() ->
            flow.refresh(new OAuthCredential("a", "rt", 0, null)));
        assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("invalid_grant");
    }

    // ── helpers ─────────────────────────────────────────────

    private static long epochNow() {
        return java.time.Instant.now().getEpochSecond();
    }

    private static Map<String, String> queryOf(String url) {
        var map = new java.util.HashMap<String, String>();
        var query = URI.create(url).getRawQuery();
        for (var pair : query.split("&")) {
            var parts = pair.split("=", 2);
            map.put(parts[0], parts.length > 1
                ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }

    private static void hitCallback(String redirectUri, String query) {
        var uri = URI.create(redirectUri.replace("localhost", "127.0.0.1") + query);
        try (var client = HttpClient.newHttpClient()) {
            client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 脚本化交互：select 答案/失败可配，manual 回调拿 authUrl 生成粘贴内容。 */
    private static final class ScriptedInteraction implements OAuthInteraction {
        private final AtomicReference<String> authUrl;
        private String selectAnswer;
        private RuntimeException selectFailure;
        private Function<String, String> manual = message -> "";
        private java.util.function.Consumer<String> onSelect = message -> { };

        ScriptedInteraction(AtomicReference<String> authUrl) {
            this.authUrl = authUrl;
        }

        ScriptedInteraction selectAnswer(String answer) {
            this.selectAnswer = answer;
            return this;
        }

        ScriptedInteraction selectFailure(String message) {
            this.selectFailure = new RuntimeException(message);
            return this;
        }

        ScriptedInteraction manual(Function<String, String> answer) {
            this.manual = answer;
            return this;
        }

        ScriptedInteraction onSelect(java.util.function.Consumer<String> sink) {
            this.onSelect = sink;
            return this;
        }

        @Override public void notify(String message) {
            var marker = "Open this URL in your browser:\n  ";
            var idx = message.indexOf(marker);
            if (idx >= 0) {
                var url = message.substring(idx + marker.length());
                var nl = url.indexOf('\n');
                authUrl.set(nl >= 0 ? url.substring(0, nl) : url);
            }
        }

        @Override public String prompt(String message) {
            return manual.apply(message);
        }

        @Override public String select(String message, List<LoginOption> options) {
            onSelect.accept(message);
            if (selectFailure != null) {
                throw selectFailure;
            }
            return selectAnswer;
        }
    }

    /** token 端点桩：固定状态/响应，记录请求体；也可当端口 blocker。 */
    private static final class TokenStub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> lastBody = new AtomicReference<>("");
        private final AtomicReference<Integer> status = new AtomicReference<>(200);
        private final AtomicReference<String> response = new AtomicReference<>(TOKEN_JSON);
        private int requestCount;

        private TokenStub() throws Exception {
            this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/token", exchange -> {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                synchronized (this) {
                    lastBody.set(body);
                    requestCount++;
                }
                var bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        static TokenStub start(int status, String response) throws Exception {
            var stub = new TokenStub();
            stub.status.set(status);
            stub.response.set(response);
            return stub;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        }

        synchronized String lastBody() {
            return lastBody.get();
        }

        synchronized int requestCount() {
            return requestCount;
        }

        void reset() {
            synchronized (this) {
                lastBody.set("");
                requestCount = 0;
            }
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
