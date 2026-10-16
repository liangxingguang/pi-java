package com.pijava.ai.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #98：federation 车道的 SDK 实测（pi {@code test/anthropic-federation-sdk.test.ts}
 * 的 java 镜像）＋ 车道门与守卫。
 *
 * <p>夹具服务器按路径分流：{@code oauth/token} 录交换体/交换头并回
 * {@code federated-token}；其余路径录鉴权头并回最小 SSE。三个实现期实测点全在这里
 * 定稿（docs/19 §1）：</p>
 * <ol>
 *   <li>{@code configurationProvider} 且无 key 能 build、首请求触发交换；</li>
 *   <li>{@code withOptions} 克隆共享 token 缓存 —— 3 次 stream ⇒ token 端点恰 1 次命中；</li>
 *   <li>不设 {@code configurationProvider} 的车道（authToken/key）零交换 —— pi 的
 *       "does not run the SDK credential chain for header-owned auth" 等价物。</li>
 * </ol>
 */
class AnthropicFederationTest {

    @TempDir
    Path tmp;

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");

    // ── 夹具服务器 ───────────────────────────────────────────────────────

    /** 按路径分流的录制桩：token 端点与其余（messages）分开计数、分开录头。 */
    private static final class FederationServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger tokenHits = new AtomicInteger();
        private final AtomicInteger messageHits = new AtomicInteger();
        private final AtomicReference<String> tokenBody = new AtomicReference<>("");
        private final AtomicReference<String> tokenBeta = new AtomicReference<>("");
        /** 每个 messages 请求的 authorization 头（缺席 ⇒ null 元素不进，另录 xApiKeys）。 */
        private final ConcurrentLinkedQueue<String> messageAuth = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> messageApiKeys = new ConcurrentLinkedQueue<>();

        FederationServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                var path = exchange.getRequestURI().getPath();
                String requestBody;
                try (InputStream in = exchange.getRequestBody();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    in.transferTo(out);
                    requestBody = out.toString(StandardCharsets.UTF_8);
                }
                if (path.contains("oauth/token")) {
                    tokenHits.incrementAndGet();
                    tokenBody.set(requestBody);
                    tokenBeta.set(header(exchange.getRequestHeaders(), "anthropic-beta"));
                    respond(exchange, 200, "application/json",
                        "{\"access_token\":\"federated-token\",\"expires_in\":3600,"
                            + "\"token_type\":\"Bearer\"}");
                } else {
                    messageHits.incrementAndGet();
                    var headers = exchange.getRequestHeaders();
                    // 队列不收 null：头缺席录哨兵，断言据此区分「缺席」与「在场」。
                    messageAuth.add(orAbsent(firstHeader(headers, "authorization")));
                    messageApiKeys.add(orAbsent(firstHeader(headers, "x-api-key")));
                    respond(exchange, 200, "text/event-stream", sseTextBlock());
                }
            });
            server.start();
        }

        /** 头缺席的哨兵值（队列不收 null）。 */
        static final String ABSENT = "<absent>";

        private static String firstHeader(com.sun.net.httpserver.Headers headers, String name) {
            var values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        private static String orAbsent(String value) {
            return value == null ? ABSENT : value;
        }

        private static String header(Map<String, List<String>> headers, String name) {
            for (var entry : headers.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).equals(name)) {
                    return entry.getValue().get(0);
                }
            }
            return "";
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                    String contentType, String body) throws IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort() + "/v1";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static String sseTextBlock() {
        return sse("message_start", "{\"type\":\"message_start\",\"message\":{"
                + "\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-5\",\"content\":[],"
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
            + sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}")
            + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + sse("message_delta", "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":5}}")
            + sse("message_stop", "{\"type\":\"message_stop\"}");
    }

    private static String sse(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    // ── 用例 ─────────────────────────────────────────────────────────────

    private Map<String, String> federationEnv() throws IOException {
        var tokenFile = tmp.resolve("identity-token");
        Files.writeString(tokenFile, "test-jwt\n"); // 尾换行：钉 SDK 的逐次重读＋trim
        var env = new LinkedHashMap<String, String>();
        env.put("ANTHROPIC_FEDERATION_RULE_ID", "rule-1");
        env.put("ANTHROPIC_ORGANIZATION_ID", "org-1");
        env.put("ANTHROPIC_IDENTITY_TOKEN_FILE", tokenFile.toString());
        env.put("ANTHROPIC_SERVICE_ACCOUNT_ID", "sa-1");
        env.put("ANTHROPIC_WORKSPACE_ID", "ws-1");
        return env;
    }

    private ApiOptions federationOptions(String baseUrl, Map<String, String> env) {
        var extra = new LinkedHashMap<String, Object>();
        extra.put("federationEnv", env);
        return new ApiOptions(baseUrl, "", Duration.ofSeconds(10), 0, extra,
            AuthKind.FEDERATION);
    }

    private static void runOneStream(ApiOptions options) {
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        var request = new StreamRequest(TARGET, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 100, 0.5);
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                iter.next();
            }
        }
    }

    /**
     * pi「exchanges the identity token once and reuses the access token across
     * requests」：车道每请求新建（生产同形），基座缓存 ⇒ 3 次 stream 只交换 1 次；
     * messages 请求带 {@code Authorization: Bearer federated-token}；交换体/交换头
     * 钉 RFC 7523 四字段 ＋ 两可选 ＋ 双 beta。
     */
    @Test
    void exchangesOnceForThreeStreams() throws Exception {
        try (var server = new FederationServer()) {
            var options = federationOptions(server.baseUrl(), federationEnv());

            runOneStream(options);
            runOneStream(options);
            runOneStream(options);

            assertThat(server.tokenHits.get())
                .as("withOptions 克隆共享基座的 token 缓存（pi :1065）").isEqualTo(1);
            assertThat(server.messageHits.get()).isEqualTo(3);
            assertThat(server.messageAuth)
                .as("messages 请求全部带交换来的 Bearer")
                .containsExactly("Bearer federated-token",
                    "Bearer federated-token", "Bearer federated-token");
            assertThat(server.messageApiKeys)
                .as("federation 车道不出现 x-api-key")
                .containsExactly(FederationServer.ABSENT, FederationServer.ABSENT,
                    FederationServer.ABSENT);

            var body = server.tokenBody.get();
            assertThat(body)
                .contains("\"grant_type\":\"urn:ietf:params:oauth:grant-type:jwt-bearer\"")
                .contains("\"assertion\":\"test-jwt\"")
                .contains("\"federation_rule_id\":\"rule-1\"")
                .contains("\"organization_id\":\"org-1\"")
                .contains("\"service_account_id\":\"sa-1\"")
                .contains("\"workspace_id\":\"ws-1\"");
            assertThat(server.tokenBeta.get())
                .as("交换头双 beta（JS SDK types.js 逐字）")
                .contains("oauth-2025-04-20").contains("oidc-federation-2026-04-01");
        }
    }

    /**
     * 守卫（pi「does not run the SDK credential chain for header-owned auth」）：
     * federation 变量在场但凭证自有（BEARER）⇒ 不设 configurationProvider，
     * token 端点零命中，请求头就是自有 Bearer。
     */
    @Test
    void headerOwnedAuthNeverRunsTheCredentialChain() throws Exception {
        try (var server = new FederationServer()) {
            var extra = new LinkedHashMap<String, Object>();
            extra.put("federationEnv", federationEnv());
            var options = new ApiOptions(server.baseUrl(), "bearer-tok",
                Duration.ofSeconds(10), 0, extra, AuthKind.BEARER);

            runOneStream(options);

            assertThat(server.tokenHits.get()).as("header 自有鉴权 ⇒ 零交换").isZero();
            assertThat(server.messageHits.get()).isEqualTo(1);
            assertThat(server.messageAuth).containsExactly("Bearer bearer-tok");
        }
    }

    /** pi {@code hasRequestAuth} 的 apiKey 半边：kind 是 FEDERATION 但值非空 ⇒ key 赢。 */
    @Test
    void explicitKeyWinsOverFederation() throws Exception {
        try (var server = new FederationServer()) {
            var extra = new LinkedHashMap<String, Object>();
            extra.put("federationEnv", federationEnv());
            var options = new ApiOptions(server.baseUrl(), "sk-direct",
                Duration.ofSeconds(10), 0, extra, AuthKind.FEDERATION);

            runOneStream(options);

            assertThat(server.tokenHits.get()).isZero();
            assertThat(server.messageApiKeys).containsExactly("sk-direct");
        }
    }

    /**
     * pi {@code hasRequestAuth} 的 headers 半边：models.json 带来 Authorization 头 ⇒
     * header 自有鉴权，federation 不抢（pi :355）。envVar 为 null 时车道落「local」
     * 占位（本地无鉴权 provider 的既有路径），关键断言是 token 端点零命中。
     */
    @Test
    void authorizationHeaderWinsOverFederation() throws Exception {
        try (var server = new FederationServer()) {
            var extra = new LinkedHashMap<String, Object>();
            extra.put("federationEnv", federationEnv());
            extra.put("headers", Map.of("Authorization", "Bearer header-owned"));
            var options = new ApiOptions(server.baseUrl(), "",
                Duration.ofSeconds(10), 0, extra, AuthKind.FEDERATION);

            var api = new AnthropicMessagesApi(options, null);
            var request = new StreamRequest(TARGET, null,
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), 100, 0.5);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            }

            assertThat(server.tokenHits.get()).isZero();
            assertThat(server.messageAuth).containsExactly("Bearer header-owned");
        }
    }

    /** 缺一必需 ⇒ 不落 federation，走既有「No API key」抛（pi assertRequestAuth 等价）。 */
    @Test
    void incompleteFederationFallsBackToNoApiKeyError() throws Exception {
        var env = federationEnv();
        env.remove("ANTHROPIC_IDENTITY_TOKEN_FILE");
        var extra = new LinkedHashMap<String, Object>();
        extra.put("federationEnv", env);
        var options = new ApiOptions("http://localhost:1/v1", "",
            Duration.ofSeconds(5), 0, extra, AuthKind.FEDERATION);

        assertThatThrownBy(() -> new AnthropicMessagesApi(options, "PI_TEST_UNSET_KEY"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No API key");
    }

    /** extra 读取：三必需齐 ⇒ 五值齐收；缺任一 ⇒ null（＝ pi undefined）。 */
    @Test
    void federationEnvOfReadsBridgedExtra() throws Exception {
        var env = federationEnv();
        var extra = new LinkedHashMap<String, Object>();
        extra.put("federationEnv", env);
        var options = new ApiOptions("", "sk", Duration.ofSeconds(5), 0, extra);

        assertThat(AnthropicFederationSupport.federationEnvOf(options))
            .containsEntry("ANTHROPIC_FEDERATION_RULE_ID", "rule-1")
            .containsEntry("ANTHROPIC_WORKSPACE_ID", "ws-1")
            .hasSize(5);

        env.remove("ANTHROPIC_ORGANIZATION_ID");
        assertThat(AnthropicFederationSupport.federationEnvOf(options)).isNull();
    }

    /** hasAuthHeader：三名大小写不敏感、blank 不算（pi hasHeader :306-313）。 */
    @Test
    void hasAuthHeaderMatchesPiRules() {
        assertThat(AnthropicFederationSupport.hasAuthHeader(
            optionsWithHeaders(Map.of("Authorization", "Bearer x")))).isTrue();
        assertThat(AnthropicFederationSupport.hasAuthHeader(
            optionsWithHeaders(Map.of("X-API-KEY", "k")))).isTrue();
        assertThat(AnthropicFederationSupport.hasAuthHeader(
            optionsWithHeaders(Map.of("cf-aig-authorization", "k")))).isTrue();
        assertThat(AnthropicFederationSupport.hasAuthHeader(
            optionsWithHeaders(Map.of("authorization", "  ")))).isFalse();
        assertThat(AnthropicFederationSupport.hasAuthHeader(
            optionsWithHeaders(Map.of("x-custom", "k")))).isFalse();
    }

    private static ApiOptions optionsWithHeaders(Map<String, String> headers) {
        var extra = new LinkedHashMap<String, Object>();
        extra.put("headers", headers);
        return new ApiOptions("", "", Duration.ofSeconds(5), 0, extra);
    }
}
