package com.pijava.ai.auth;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 对应 pi {@code test/openai-chatgpt-oauth.test.ts}：动态客户端参数、签发 client_id、
 * 直连 scope 门、设备 ID、刷新 client_id/resource/scopes、固定端口占用硬失败。
 *
 * <p>回调端口经包私有构造器注入空闲端口（JDK {@code HttpServer.stop()} 在 Windows
 * 上异步解绑，固定 1455 在同 JVM 紧接重绑会抖；生产值 1455 由
 * {@link #defaultCallbackPortIs1455()} 钉住）。</p>
 */
class ChatGptOAuthFlowTest {

    private static final String DEVICE_ID = "e61bbe28-07ef-466d-8e5d-a344f94ab305";
    private static final String REQUIRED_SCOPE =
        "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";

    private static String tokenResponse(String scope) {
        return "{\"access_token\":\"access-token\",\"refresh_token\":\"refresh-token\","
            + "\"expires_in\":3600,\"id_token\":\"id-token\",\"scope\":\"" + scope + "\"}";
    }

    private record FlowAndPort(ChatGptOAuthFlow flow, int port) {}

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static FlowAndPort newFlow(TokenStub token) throws Exception {
        var port = freePort();
        return new FlowAndPort(new ChatGptOAuthFlow(
            Duration.ofSeconds(15), "127.0.0.1", token.url(), port), port);
    }

    // ── 登录 ─────────────────────────────────────────────────

    @Test
    void registersUserOwnedClientAndStoresIssuedIdAndScopes() throws Exception {
        var token = TokenStub.start(tokenResponse(REQUIRED_SCOPE));
        var flowAndPort = newFlow(token);
        var flow = flowAndPort.flow();
        var authUrl = new AtomicReference<String>();

        var credential = flow.login(
            new ScriptedInteraction(authUrl).manual(callbackFor(authUrl, "oaiapp_issued")),
            new ChatGptOAuthFlow.LoginHints(() -> DEVICE_ID, "Pi"));

        var params = queryOf(authUrl.get());
        assertThat(params).containsEntry("client_id", "dynamic_agent_client");
        assertThat(params).containsEntry("agent_name_hint", "Pi");
        assertThat(params).containsEntry("ext_agent_host_id", "urn:uuid:" + DEVICE_ID);
        assertThat(params).containsEntry("scope", REQUIRED_SCOPE);
        assertThat(params).containsEntry("redirect_uri", flow.redirectUri());
        assertThat(params).containsEntry("resource", "https://api.openai.com/v1");
        assertThat(params).containsEntry("code_challenge_method", "S256");
        assertThat(params.get("state")).isNotBlank();
        assertThat(params.get("nonce")).isNotBlank();
        assertThat(params.get("code_challenge")).isNotBlank();

        var body = formDecode(token.lastBody());
        assertThat(body).containsEntry("grant_type", "authorization_code");
        assertThat(body).containsEntry("client_id", "oaiapp_issued");
        assertThat(body).containsEntry("code", "authorization-code");
        assertThat(body).containsEntry("redirect_uri", flow.redirectUri());
        assertThat(body).containsEntry("resource", "https://api.openai.com/v1");
        assertThat(body.get("code_verifier")).isNotBlank();

        assertThat(credential.accessToken()).isEqualTo("access-token");
        assertThat(credential.refreshToken()).isEqualTo("refresh-token");
        assertThat(credential.clientId()).isEqualTo("oaiapp_issued");
        assertThat(credential.scopes()).containsExactly(REQUIRED_SCOPE.split(" "));
    }

    @Test
    void usesAgentNameAsHint() throws Exception {
        var token = TokenStub.start(tokenResponse(REQUIRED_SCOPE));
        var flow = newFlow(token).flow();
        var authUrl = new AtomicReference<String>();

        flow.login(new ScriptedInteraction(authUrl).manual(callbackFor(authUrl, "oaiapp_issued")),
            new ChatGptOAuthFlow.LoginHints(() -> DEVICE_ID, "my-app"));

        assertThat(queryOf(authUrl.get())).containsEntry("agent_name_hint", "my-app");
    }

    @Test
    void rejectsCallbackWithoutIssuedClientIdWithoutHittingTokenEndpoint() throws Exception {
        var token = TokenStub.start(tokenResponse(REQUIRED_SCOPE));
        var flow = newFlow(token).flow();
        var authUrl = new AtomicReference<String>();

        var thrown = catchThrowable(() -> flow.login(
            new ScriptedInteraction(authUrl).manual(callbackFor(authUrl, null)),
            new ChatGptOAuthFlow.LoginHints(() -> DEVICE_ID, "Pi")));

        assertThat(thrown).isInstanceOf(IOException.class)
            .hasMessageContaining("issued client ID");
        assertThat(token.requestCount()).isZero();
    }

    @Test
    void rejectsGrantWithoutDirectTokenScope() throws Exception {
        var token = TokenStub.start(
            tokenResponse("openid profile email offline_access resource.invoke"));
        var flow = newFlow(token).flow();
        var authUrl = new AtomicReference<String>();

        var thrown = catchThrowable(() -> flow.login(
            new ScriptedInteraction(authUrl).manual(callbackFor(authUrl, "oaiapp_issued")),
            new ChatGptOAuthFlow.LoginHints(() -> DEVICE_ID, "Pi")));

        assertThat(thrown).isInstanceOf(IOException.class)
            .hasMessageContaining("grant did not include chatgpt.tokens.use.direct");
    }

    @Test
    void requiresDeviceIdBeforeStartingAuthorization() throws Exception {
        var token = TokenStub.start(tokenResponse(REQUIRED_SCOPE));
        var flowAndPort = newFlow(token);
        var flow = flowAndPort.flow();
        var authUrl = new AtomicReference<String>();

        var missing = catchThrowable(() -> flow.login(
            new ScriptedInteraction(authUrl),
            new ChatGptOAuthFlow.LoginHints(() -> null, "Pi")));
        var notUuid = catchThrowable(() -> flow.login(
            new ScriptedInteraction(authUrl),
            new ChatGptOAuthFlow.LoginHints(() -> "not-a-uuid", "Pi")));

        assertThat(missing).hasMessageContaining("requires a device ID");
        assertThat(notUuid).hasMessageContaining("requires a device ID");
        assertThat(authUrl.get()).isNull(); // 授权 URL 从未发出
        assertThat(token.requestCount()).isZero();
        // 回调端口从未被绑定（失败后应可立即占住）。
        try (var probe = new ServerSocket(flowAndPort.port(), 0,
            InetAddress.getByName("127.0.0.1"))) {
            assertThat(probe.isBound()).isTrue();
        }
    }

    @Test
    void rejectsPastedUrlFromDifferentOrigin() throws Exception {
        var token = TokenStub.start(tokenResponse(REQUIRED_SCOPE));
        var flow = newFlow(token).flow();
        var authUrl = new AtomicReference<String>();

        var thrown = catchThrowable(() -> flow.login(
            new ScriptedInteraction(authUrl).manual(message ->
                "http://127.0.0.1:9999/auth/callback?code=x&state="
                    + queryOf(authUrl.get()).get("state") + "&client_id=oaiapp_issued"),
            new ChatGptOAuthFlow.LoginHints(() -> DEVICE_ID, "Pi")));

        assertThat(thrown).isInstanceOf(IOException.class)
            .hasMessageContaining("must start with " + flow.redirectUri());
    }

    @Test
    void hardFailsWhenFixedCallbackPortIsInUse() throws Exception {
        var port = freePort();
        var blocker = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        blocker.start();
        try {
            var token = TokenStub.start(tokenResponse(REQUIRED_SCOPE));
            var flow = new ChatGptOAuthFlow(
                Duration.ofSeconds(15), "127.0.0.1", token.url(), port);

            var thrown = catchThrowable(() -> flow.login(
                new ScriptedInteraction(new AtomicReference<>()),
                new ChatGptOAuthFlow.LoginHints(() -> DEVICE_ID, "Pi")));

            assertThat(thrown).isInstanceOf(IOException.class)
                .hasMessageContaining("Port " + port + " is in use")
                .hasMessageContaining("Codex CLI");
            assertThat(token.requestCount()).isZero();
        } finally {
            blocker.stop(0);
        }
    }

    @Test
    void defaultCallbackPortIs1455() {
        var flow = new ChatGptOAuthFlow();
        assertThat(flow.callbackPort()).isEqualTo(1455);
        assertThat(flow.redirectUri()).isEqualTo("http://127.0.0.1:1455/auth/callback");
    }

    // ── 刷新 ─────────────────────────────────────────────────

    @Test
    void requiresRefreshResponsesToRotateRefreshToken() throws Exception {
        var token = TokenStub.start(
            tokenResponse(REQUIRED_SCOPE).replace("\"refresh_token\":\"refresh-token\",", ""));
        var flow = newFlow(token).flow();

        var thrown = catchThrowable(() ->
            flow.refresh(connectedCredential("oaiapp_existing")));

        assertThat(thrown).isInstanceOf(IOException.class)
            .hasMessageContaining("invalid refresh_token");
    }

    @Test
    void refreshUsesIssuedClientIdAndResourceAndReplacesScopes() throws Exception {
        var token = TokenStub.start(
            tokenResponse(REQUIRED_SCOPE)
                .replace("access-token", "new-access").replace("refresh-token", "new-refresh"));
        var flow = newFlow(token).flow();

        var before = System.currentTimeMillis() / 1000;
        var credential = flow.refresh(connectedCredential("oaiapp_existing"));
        var after = System.currentTimeMillis() / 1000;

        var body = formDecode(token.lastBody());
        assertThat(body).containsEntry("grant_type", "refresh_token");
        assertThat(body).containsEntry("client_id", "oaiapp_existing");
        assertThat(body).containsEntry("refresh_token", "old-refresh");
        assertThat(body).containsEntry("resource", "https://api.openai.com/v1");
        assertThat(body).doesNotContainKey("scope");

        assertThat(credential.accessToken()).isEqualTo("new-access");
        assertThat(credential.refreshToken()).isEqualTo("new-refresh");
        assertThat(credential.clientId()).isEqualTo("oaiapp_existing");
        assertThat(credential.scopes()).containsExactly(REQUIRED_SCOPE.split(" "));
        // expires_in 3600，提前 3 分钟。
        assertThat(credential.expiresAtEpochSec()).isBetween(before + 3600 - 180, after + 3600 - 180);
    }

    @Test
    void refreshWithoutIssuedClientIdAsksToReconnect() throws Exception {
        var token = TokenStub.start(tokenResponse(REQUIRED_SCOPE));
        var flow = newFlow(token).flow();

        var thrown = catchThrowable(() ->
            flow.refresh(new OAuthCredential("a", "r", 0, null)));

        assertThat(thrown).isInstanceOf(IOException.class)
            .hasMessageContaining("does not contain an issued client ID; reconnect ChatGPT");
    }

    @Test
    void refreshDoesNotRequireIdToken() throws Exception {
        // 刷新响应无 id_token 仍成功（在场性检查只属于授权交换）。
        var token = TokenStub.start(
            "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":3600,\"scope\":\""
                + REQUIRED_SCOPE + "\"}");
        var flow = newFlow(token).flow();

        var credential = flow.refresh(connectedCredential("oaiapp_existing"));

        assertThat(credential.clientId()).isEqualTo("oaiapp_existing");
    }

    // ── helpers ──────────────────────────────────────────────

    private static OAuthCredential connectedCredential(String clientId) {
        return new OAuthCredential("old-access", "old-refresh", 0, null,
            clientId, List.of(REQUIRED_SCOPE.split(" ")));
    }

    /** 从捕获的 authorize URL 拼出带签发 clientId 的完整回调 URL（模拟手工粘贴）。 */
    private static Function<String, String> callbackFor(AtomicReference<String> authUrl,
                                                         String issuedClientId) {
        return message -> {
            var params = queryOf(authUrl.get());
            var url = params.get("redirect_uri") + "?code=authorization-code&state="
                + params.get("state");
            if (issuedClientId != null) {
                url += "&client_id=" + issuedClientId;
            }
            return url;
        };
    }

    private static Map<String, String> queryOf(String url) {
        var map = new LinkedHashMap<String, String>();
        var query = URI.create(url).getRawQuery();
        for (var pair : query.split("&")) {
            var parts = pair.split("=", 2);
            map.put(parts[0], parts.length > 1
                ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }

    private static Map<String, String> formDecode(String form) {
        var map = new LinkedHashMap<String, String>();
        for (var pair : form.split("&")) {
            var parts = pair.split("=", 2);
            map.put(parts[0], parts.length > 1
                ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }

    private static final class ScriptedInteraction implements OAuthInteraction {
        private final AtomicReference<String> authUrl;
        private Function<String, String> manual = message -> "";

        ScriptedInteraction(AtomicReference<String> authUrl) {
            this.authUrl = authUrl;
        }

        ScriptedInteraction manual(Function<String, String> answer) {
            this.manual = answer;
            return this;
        }

        @Override public void notify(String message) {
            var marker = "Open this URL in your browser:\n  ";
            var idx = message.indexOf(marker);
            if (idx >= 0) {
                authUrl.set(message.substring(idx + marker.length()).strip());
            }
        }

        @Override public String prompt(String message) {
            return manual.apply(message);
        }
    }

    private static final class TokenStub {
        private final HttpServer server;
        private final AtomicReference<String> lastBody = new AtomicReference<>("");
        private final String response;
        private int requestCount;

        private TokenStub(String response) throws Exception {
            this.response = response;
            this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/token", exchange -> {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                synchronized (this) {
                    lastBody.set(body);
                    requestCount++;
                }
                var bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        static TokenStub start(String response) throws Exception {
            return new TokenStub(response);
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
    }
}
