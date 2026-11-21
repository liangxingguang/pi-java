package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/12} S7b：车道接入的**两轮观测**（pi {@code anthropic-messages.ts:352-475}）。
 *
 * <p>首轮发 {@code thread:{type:"create"}} ＋ threads beta；次轮在「上次前缀未变」时只发
 * 新增消息（无 {@code system}／{@code tools}）并带 {@code previous_message_id}；前缀变了
 * 就回到 {@code create}；端点不在白名单时**一个 {@code thread} 键都不发**。</p>
 *
 * <p>⚠️ 测试端点不在 {@code api.anthropic.com} 白名单内，故用 {@code extra["messageThreads"]}
 * 显式开启（{@code docs/12} D-1 的 compat 替身；生产路径走端点推导）。</p>
 */
class AnthropicThreadsWireTest {

    private static final String SESSION = "s-threads-wire";

    @AfterEach
    void clearSessionState() {
        AnthropicThreadState.clear(null);
    }

    @Test
    void theFirstRoundCreatesAThread() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(success());

            run(server.baseUrl(), conversation("base prompt", false), Map.of("messageThreads", true));

            assertThat(server.requestCount()).isEqualTo(1);
            var body = server.body();
            assertThat(body).contains("\"thread\":{\"type\":\"create\"}");
            assertThat(body).doesNotContain("previous_message_id");
            assertThat(server.headers().get("anthropic-beta"))
                .contains(AnthropicBetaFeatures.MESSAGE_THREADS_BETA);
        }
    }

    @Test
    void theSecondRoundContinuesTheThreadWithOnlyTheNewMessages() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(success());
            server.enqueue(success());
            var extra = Map.<String, Object>of("messageThreads", true);

            run(server.baseUrl(), conversation("base prompt", false), extra);
            run(server.baseUrl(), conversation("base prompt", true), extra);

            assertThat(server.requestCount()).isEqualTo(2);
            var second = server.bodies().get(1);
            assertThat(second).contains("\"thread\":{\"type\":\"continue\"");
            assertThat(second).contains("\"previous_message_id\":\"msg_1\"");
            assertThat(second).as("continue 省略 system 与 tools").doesNotContain("\"system\"");
            assertThat(second).doesNotContain("\"tools\"");
            assertThat(second).contains("next question");
            assertThat(second).as("只发新增").doesNotContain("hello there");
        }
    }

    @Test
    void aChangedSystemPromptStartsANewThread() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(success());
            server.enqueue(success());
            var extra = Map.<String, Object>of("messageThreads", true);

            run(server.baseUrl(), conversation("base prompt", false), extra);
            run(server.baseUrl(), conversation("a different prompt", true), extra);

            assertThat(server.requestCount()).isEqualTo(2);
            var second = server.bodies().get(1);
            assertThat(second).contains("\"thread\":{\"type\":\"create\"}");
            assertThat(second).as("新线程要带完整请求（含 system 与全部消息）")
                .contains("\"system\"")
                .contains("hello there");
        }
    }

    @Test
    void anEndpointOutsideTheThreadsAllowlistSendsNoThreadField() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(success());
            server.enqueue(success());

            // 无 extra ⇒ 按端点推导；localhost 不在白名单 ⇒ 不发 thread，也不发 threads beta。
            run(server.baseUrl(), conversation("base prompt", false), Map.of());
            run(server.baseUrl(), conversation("base prompt", true), Map.of());

            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(server.bodies()).allSatisfy(body -> assertThat(body)
                .doesNotContain("\"thread\""));
            assertThat(server.headers().getOrDefault("anthropic-beta", ""))
                .doesNotContain(AnthropicBetaFeatures.MESSAGE_THREADS_BETA);
        }
    }

    @Test
    void disableContinuationSendsNoThreadAtAll() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(success());
            server.enqueue(success());
            var extra = Map.<String, Object>of(
                "messageThreads", true, "disableContinuation", true);

            run(server.baseUrl(), conversation("base prompt", false), extra);
            run(server.baseUrl(), conversation("base prompt", true), extra);

            assertThat(server.requestCount()).isEqualTo(2);
            // 关的是「发不发 thread」：连 create 都不发，但 rounds beta 仍照发（pi :1116 与
            // :908 是两处判据）。
            assertThat(server.bodies()).allSatisfy(body ->
                assertThat(body).doesNotContain("\"thread\""));
            assertThat(server.headers().getOrDefault("anthropic-beta", ""))
                .contains(AnthropicBetaFeatures.MESSAGE_THREADS_BETA);
        }
    }

    @Test
    void aRejectedContinueIsResentOnceAsANewThread() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(success());
            // 第二轮：续线程被拒（旧线程没了）⇒ 以 create 完整重发一次。
            server.enqueue(new RecordingHttpServer.Response(400,
                Map.of("Content-Type", "application/json"),
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                    .concat("\"message\":\"thread not found\",\"details\":"
                        + "{\"error_code\":\"thread_not_found\"}}}")
                    .getBytes(StandardCharsets.UTF_8)));
            server.enqueue(success());
            var extra = Map.<String, Object>of("messageThreads", true);

            run(server.baseUrl(), conversation("base prompt", false), extra);
            var events = run(server.baseUrl(), conversation("base prompt", true), extra);

            assertThat(server.requestCount()).isEqualTo(3);
            var retried = server.bodies().get(2);
            assertThat(retried).contains("\"thread\":{\"type\":\"create\"}");
            assertThat(retried).as("create 重发要带完整请求").contains("\"system\"");
            var diagnostic = diagnosticsOf(events).get(0);
            assertThat(diagnostic.type()).isEqualTo("anthropic_thread_fallback");
            assertThat(diagnostic.details().path("reason").asText())
                .isEqualTo("thread_not_found");
            assertThat(diagnostic.error().message()).contains("thread not found");
        }
    }

    @Test
    void aThreadShaped400DropsTheFieldAndTurnsThreadsOff() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(success());
            server.enqueue(threadUnsupported400());
            server.enqueue(success());
            server.enqueue(success());
            var extra = Map.<String, Object>of("messageThreads", true);

            run(server.baseUrl(), conversation("base prompt", false), extra);
            var events = run(server.baseUrl(), conversation("base prompt", true), extra);

            assertThat(server.requestCount()).isEqualTo(3);
            var retried = server.bodies().get(2);
            assertThat(retried).as("去 thread 重发").doesNotContain("\"thread\"");
            assertThat(retried).as("仍是同一份完整请求").contains("\"system\"");
            assertThat(diagnosticsOf(events).get(0).details().path("reason").asText())
                .isEqualTo("400");

            // off 是终局：第三轮连 create 都不发，也不带 threads beta。
            run(server.baseUrl(), conversation("base prompt", false), extra);
            assertThat(server.requestCount()).isEqualTo(4);
            assertThat(server.bodies().get(3)).doesNotContain("\"thread\"");
            assertThat(AnthropicThreadState.get(SESSION)).isEmpty();
            assertThat(AnthropicThreadState.isOff(SESSION)).isTrue();
        }
    }

    @Test
    void anUnrelatedRejectionIsNotResent() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(success());
            server.enqueue(new RecordingHttpServer.Response(400,
                Map.of("Content-Type", "application/json"),
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                    .concat("\"message\":\"prompt is too long\"}}")
                    .getBytes(StandardCharsets.UTF_8)));
            var extra = Map.<String, Object>of("messageThreads", true);

            run(server.baseUrl(), conversation("base prompt", false), extra);
            var events = run(server.baseUrl(), conversation("base prompt", true), extra);

            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    /**
     * 两轮转录：{@code second == false} 是「一问」，{@code true} 是「一问一答再一问」
     * —— 第二轮的行数必须变多，且前缀（含上一轮的回复行）逐字不变。
     */
    private static List<Message> conversation(String system, boolean second) {
        var messages = new ArrayList<Message>();
        messages.add(new Message.SystemMessage(system, Instant.EPOCH, Map.of(),
            List.of(), List.of()));
        messages.add(new Message.UserMessage(List.of(new ContentBlock.TextContent("hello there"))));
        if (second) {
            messages.add(new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("hi")), StopReason.STOP, null,
                "anthropic-messages", "anthropic", "claude-sonnet-5", null, null, null, null));
            messages.add(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("next question"))));
        }
        return List.copyOf(messages);
    }

    private static List<StreamEvent> run(String baseUrl, List<Message> messages,
                                         Map<String, Object> extra) {
        var settings = new java.util.LinkedHashMap<String, Object>(extra);
        settings.put("sessionId", SESSION);
        var api = new AnthropicMessagesApi(
            new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0, settings),
            "ANTHROPIC_API_KEY");
        var request = new StreamRequest(ModelId.of("anthropic", "claude-sonnet-5"), null,
            messages, List.of(), -1, -1);
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                // 消费到底：记账发生在流尾之后。
                var event = iter.next();
                events.add(event);
                if (event instanceof StreamEvent.StreamError error) {
                    return events;
                }
            }
        }
        return events;
    }

    /** 最后一条终局事件里的诊断（S8：{@code anthropic_thread_fallback}）。 */
    private static java.util.List<com.pijava.ai.message.AssistantMessageDiagnostic> diagnosticsOf(
            List<StreamEvent> events) {
        return events.stream()
            .filter(e -> e instanceof StreamEvent.StreamDone)
            .map(e -> (StreamEvent.StreamDone) e)
            .reduce((first, second) -> second)
            .orElseThrow()
            .partial().diagnostics();
    }

    /** 没有 code 的 400：pi 的第二支靠「400 ＋ 文案含 thread」识别。 */
    private static RecordingHttpServer.Response threadUnsupported400() {
        return new RecordingHttpServer.Response(400, Map.of("Content-Type", "application/json"),
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                .concat("\"message\":\"thread: Extra inputs are not permitted\"}}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static RecordingHttpServer.Response success() {
        var body = concat(
            sse("message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                    + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5\","
                    + "\"stop_reason\":null,\"stop_sequence\":null,"
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}"),
            sse("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,"
                    + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
            sse("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                    + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}"),
            sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
            sse("message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                    + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}"),
            sse("message_stop", "{\"type\":\"message_stop\"}"));
        return new RecordingHttpServer.Response(200,
            Map.of("Content-Type", "text/event-stream"), body);
    }

    private static byte[] sse(String event, String data) {
        return ("event: " + event + "\ndata: " + data + "\n\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

}
