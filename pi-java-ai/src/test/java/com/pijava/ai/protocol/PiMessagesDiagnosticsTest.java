package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessageDiagnostic;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.utils.ProviderErrors;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B148：pi-messages 车道的流内诊断（对位 pi {@code pi-messages.test.ts:218-245}
 * 与 {@code pi-messages.ts:133-180/:336-341}）：
 * <ul>
 *   <li>HTTP 故障（非 2xx）⇒ {@code pi_messages_response_failure} 诊断，
 *       details 信封逐字（结构化体挂原始 error；非 JSON 体挂截断 body）；</li>
 *   <li>200 SSE 流内 error 事件 <b>不</b>附失败诊断；</li>
 *   <li>done/error 帧的 rewrite ⇒ {@code pi_messages_rewrite} 诊断。</li>
 * </ul>
 */
class PiMessagesDiagnosticsTest {

    @Test
    void httpFailureCarriesResponseFailureDiagnostic() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(401,
                Map.of("Content-Type", "application/json"),
                "{\"error\":{\"message\":\"Token expired\",\"code\":\"unauthorized\"}}"
                    .getBytes(StandardCharsets.UTF_8)));

            var msg = settle(server);

            assertThat(msg.errorMessage())
                .contains("401", "Token expired", "unauthorized");
            var diagnostics = msg.diagnostics();
            assertThat(diagnostics).hasSize(1);
            var diagnostic = diagnostics.get(0);
            assertThat(diagnostic.type()).isEqualTo("pi_messages_response_failure");
            assertThat(diagnostic.timestamp()).isPositive();

            var error = diagnostic.error();
            assertThat(error.name()).isEqualTo("PiMessagesResponseError");
            assertThat(error.message()).contains("401", "Token expired");
            assertThat(error.stack()).contains("PiHttpException");
            assertThat(error.code().asText()).isEqualTo("unauthorized");

            var details = diagnostic.details();
            assertThat(details.path("version").asInt()).isEqualTo(1);
            assertThat(details.path("provider").asText()).isEqualTo("radius");
            assertThat(details.path("model").asText()).isEqualTo("gateway-model");
            assertThat(details.path("url").asText()).endsWith("/v1/messages");
            assertThat(details.path("status").asInt()).isEqualTo(401);
            assertThat(details.path("statusText").asText()).isEqualTo("Unauthorized");
            assertThat(details.path("timestampMs").asLong()).isPositive();
            assertThat(details.path("error").path("code").asText()).isEqualTo("unauthorized");
            assertThat(details.has("body")).isFalse();
        }
    }

    @Test
    void nonJsonFailureBodyLandsTruncatedInDetails() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(413,
                Map.of("Content-Type", "text/plain"),
                "policy overflow".getBytes(StandardCharsets.UTF_8)));

            var diagnostic = soleDiagnostic(settle(server));

            var details = diagnostic.details();
            assertThat(details.has("error")).isFalse();
            assertThat(details.path("body").asText()).isEqualTo("policy overflow");
        }
    }

    /** 裸 body 截断在 8192 字符 + U+2026（pi {@code truncateDiagnosticString}）。 */
    @Test
    void longFailureBodyIsTruncatedTo8192PlusEllipsis() throws Exception {
        try (var server = new RecordingHttpServer()) {
            String raw = "z".repeat(8200);
            server.enqueue(new RecordingHttpServer.Response(400,
                Map.of("Content-Type", "text/plain"),
                raw.getBytes(StandardCharsets.UTF_8)));

            var diagnostic = soleDiagnostic(settle(server));

            String body = diagnostic.details().path("body").asText();
            assertThat(body).hasSize(ProviderErrors.DIAGNOSTIC_BODY_MAX + 1)
                .startsWith("z".repeat(8192))
                .endsWith("…");
        }
    }

    /** 流内 SSE error 事件不附失败诊断（只有 HTTP 信封附）。 */
    @Test
    void inStreamSseErrorHasNoDiagnostic() throws Exception {
        try (var server = new RecordingHttpServer()) {
            String sse = "data: {\"type\":\"error\",\"reason\":\"error\",\"usage\":null,"
                + "\"errorMessage\":\"Upstream failed\"}\n\n";
            server.enqueue(new RecordingHttpServer.Response(200,
                Map.of("Content-Type", "text/event-stream"),
                sse.getBytes(StandardCharsets.UTF_8)));

            var msg = settle(server);
            assertThat(msg.errorMessage()).isEqualTo("Upstream failed");
            assertThat(msg.diagnostics()).isNull();
        }
    }

    /** done 帧带 rewrite ⇒ pi_messages_rewrite 诊断（pi :173-180）。 */
    @Test
    void doneRewriteLandsAsDiagnostic() throws Exception {
        try (var server = new RecordingHttpServer()) {
            String sse = "data: {\"type\":\"start\"}\n\n"
                + "data: {\"type\":\"done\",\"reason\":\"stop\",\"usage\":null,"
                + "\"rewrite\":{\"policyId\":\"pol-1\",\"policyVersion\":1,\"changed\":true,"
                + "\"tokenCountChange\":-2,\"messageCountChange\":-1,"
                + "\"systemPromptChanged\":false}}\n\n";
            server.enqueue(new RecordingHttpServer.Response(200,
                Map.of("Content-Type", "text/event-stream"),
                sse.getBytes(StandardCharsets.UTF_8)));

            var diagnostic = soleDiagnostic(settle(server));
            assertThat(diagnostic.type()).isEqualTo("pi_messages_rewrite");
            assertThat(diagnostic.error()).isNull();
            var details = diagnostic.details();
            assertThat(details.path("policyId").asText()).isEqualTo("pol-1");
            assertThat(details.path("policyVersion").asInt()).isEqualTo(1);
            assertThat(details.path("changed").asBoolean()).isTrue();
            assertThat(details.path("tokenCountChange").asInt()).isEqualTo(-2);
            assertThat(details.path("messageCountChange").asInt()).isEqualTo(-1);
            assertThat(details.path("systemPromptChanged").asBoolean()).isFalse();
        }
    }

    /** error 帧带 rewrite 同样附诊断（pi 的 done/error 两分支都调 appendRewriteDiagnostic）。 */
    @Test
    void errorFrameRewriteLandsAsDiagnostic() throws Exception {
        try (var server = new RecordingHttpServer()) {
            String sse = "data: {\"type\":\"error\",\"reason\":\"error\",\"usage\":null,"
                + "\"errorMessage\":\"rewritten away\","
                + "\"rewrite\":{\"policyId\":\"p\",\"policyVersion\":2,\"changed\":false,"
                + "\"tokenCountChange\":0,\"messageCountChange\":0,"
                + "\"systemPromptChanged\":true}}\n\n";
            server.enqueue(new RecordingHttpServer.Response(200,
                Map.of("Content-Type", "text/event-stream"),
                sse.getBytes(StandardCharsets.UTF_8)));

            var msg = settle(server);
            assertThat(msg.stopReason()).isEqualTo(StopReason.ERROR);
            var diagnostic = soleDiagnostic(msg);
            assertThat(diagnostic.type()).isEqualTo("pi_messages_rewrite");
            assertThat(diagnostic.details().path("systemPromptChanged").asBoolean()).isTrue();
        }
    }

    // ── 脚手架 ───────────────────────────────────────────────────────────

    private static AssistantMessageDiagnostic soleDiagnostic(Message.AssistantMessage msg) {
        assertThat(msg.diagnostics()).hasSize(1);
        return msg.diagnostics().get(0);
    }

    private static Message.AssistantMessage settle(RecordingHttpServer server) {
        var options = new ApiOptions(server.baseUrl(), "test-key",
            Duration.ofSeconds(5), 0, Map.of());
        var api = new PiMessagesApi(options, "PI_MESSAGES_API_KEY");
        ModelInfo model = ModelInfo.minimal(ModelId.of("radius", "gateway-model"));
        Message user = new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi")));
        var request = new StreamRequest(model, "", List.of(user), List.of(), -1, -1);

        var result = api.send(request, options);
        assertThat(result).isInstanceOf(Message.AssistantMessage.class);
        return (Message.AssistantMessage) result;
    }
}
