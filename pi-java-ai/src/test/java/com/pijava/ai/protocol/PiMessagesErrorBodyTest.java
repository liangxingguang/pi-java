package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B224：pi-messages 车道 HTTP 错误响应文案的车道级真 HTTP 夹具（设计 原 docs/49 §6.1）。
 *
 * <p>对齐 pi {@code pi-messages.ts:124-131}：
 * {@code `<status> <statusText>: <message ?? body> (<code>)`}。phrase 是 JDK
 * 拿不到的 reason phrase，用 RFC 标准短语表重放（原 docs/49 §4.2）。</p>
 */
class PiMessagesErrorBodyTest {

    /** E1：结构化错误体 ⇒ message + code 后缀（pi 测试 :222-236 同形）。 */
    @Test
    void structuredErrorUsesMessageAndCodeSuffix() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(401,
                Map.of("Content-Type", "application/json"),
                "{\"error\":{\"message\":\"Token expired\",\"code\":\"unauthorized\"}}"
                    .getBytes(StandardCharsets.UTF_8)));

            var msg = settle(server);

            assertThat(msg.errorMessage())
                .isEqualTo("401 Unauthorized: Token expired (unauthorized)");
            // pi 的低层是单次 fetch：失败不重试。
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    /** E2：400 空体 ⇒ parse 失败，suffix 落空串；逐字 {@code 400 Bad Request: }（尾留空格）。 */
    @Test
    void bodyless400KeepsEmptySuffix() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(400,
                Map.of("Content-Type", "application/json"), new byte[0]));

            var msg = settle(server);

            assertThat(msg.errorMessage()).isEqualTo("400 Bad Request: ");
        }
    }

    /**
     * E2b：结构化体但 message/code 都是空串 ⇒ suffix 空、<b>不</b>回落非空 body
     * （JS {@code ??} 只挡 null/undefined，不挡空串；探针 M4 守这道门）。
     */
    @Test
    void emptyStringMessageDoesNotFallBackToNonEmptyBody() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(400,
                Map.of("Content-Type", "application/json"),
                "{\"error\":{\"message\":\"\",\"code\":\"\"}}"
                    .getBytes(StandardCharsets.UTF_8)));

            var msg = settle(server);

            assertThat(msg.errorMessage()).isEqualTo("400 Bad Request: ");
        }
    }

    /** E3：非 JSON 纯文本体 ⇒ 裸 body 兜底（pi 的 {@code message ?? body}）。 */
    @Test
    void plainText413FallsBackToRawBody() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(413,
                Map.of("Content-Type", "text/plain"),
                "policy overflow".getBytes(StandardCharsets.UTF_8)));

            var msg = settle(server);

            assertThat(msg.errorMessage())
                .isEqualTo("413 Payload Too Large: policy overflow");
        }
    }

    /** E4：未知状态码 ⇒ phrase 空串，精确复现 pi 空 statusText 的 {@code "599 : x"} 形状。 */
    @Test
    void unknownStatusPhraseIsEmpty() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(599,
                Map.of("Content-Type", "text/plain"),
                "weird".getBytes(StandardCharsets.UTF_8)));

            var msg = settle(server);

            assertThat(msg.errorMessage()).isEqualTo("599 : weird");
        }
    }

    /** E5：抛出文案不截断（8192 截断是诊断 details 的事，随 B225）。 */
    @Test
    void longBodyIsNotTruncatedInErrorMessage() throws Exception {
        try (var server = new RecordingHttpServer()) {
            String raw = "x".repeat(10000);
            server.enqueue(new RecordingHttpServer.Response(400,
                Map.of("Content-Type", "text/plain"),
                raw.getBytes(StandardCharsets.UTF_8)));

            var msg = settle(server);
            String text = msg.errorMessage();

            assertThat(text).startsWith("400 Bad Request: ");
            assertThat(text.substring("400 Bad Request: ".length())).isEqualTo(raw);
            assertThat(text).doesNotContain("…");
            assertThat(text).doesNotContain("HTTP 4");
        }
    }

    /** E6（对照）：200 SSE 流内 error 事件透传事件文案，不被 HTTP 格式重成形。 */
    @Test
    void inStreamSseErrorPassesThroughUnchanged() throws Exception {
        try (var server = new RecordingHttpServer()) {
            String sse = "data: {\"type\":\"error\",\"reason\":\"error\",\"usage\":null,"
                + "\"errorMessage\":\"Upstream failed\"}\n\n";
            server.enqueue(new RecordingHttpServer.Response(200,
                Map.of("Content-Type", "text/event-stream"),
                sse.getBytes(StandardCharsets.UTF_8)));

            var msg = settle(server);

            assertThat(msg.errorMessage()).isEqualTo("Upstream failed");
        }
    }

    // ── 脚手架 ───────────────────────────────────────────────────────────

    private static Message.AssistantMessage settle(RecordingHttpServer server) {
        var options = new ApiOptions(server.baseUrl(), "test-key",
            Duration.ofSeconds(5), 0, Map.of());
        var api = new PiMessagesApi(options, "PI_MESSAGES_API_KEY");
        ModelInfo model = ModelInfo.minimal(ModelId.of("radius", "gateway-model"));
        Message user = new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi")));
        var request = new StreamRequest(model, "", List.of(user), List.of(), -1, -1, Map.of());

        var result = api.send(request, options);
        assertThat(result).isInstanceOf(Message.AssistantMessage.class);
        var msg = (Message.AssistantMessage) result;
        assertThat(msg.stopReason()).isEqualTo("error");
        return msg;
    }
}
