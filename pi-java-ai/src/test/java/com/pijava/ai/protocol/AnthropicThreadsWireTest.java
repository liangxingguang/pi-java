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

    private static void run(String baseUrl, List<Message> messages, Map<String, Object> extra) {
        var settings = new java.util.LinkedHashMap<String, Object>(extra);
        settings.put("sessionId", SESSION);
        var api = new AnthropicMessagesApi(
            new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0, settings),
            "ANTHROPIC_API_KEY");
        var request = new StreamRequest(ModelId.of("anthropic", "claude-sonnet-5"), null,
            messages, List.of(), -1, -1);
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                // 消费到底：记账发生在流尾之后。
                if (iter.next() instanceof StreamEvent.StreamError error) {
                    throw new AssertionError("unexpected stream error", error.error());
                }
            }
        }
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
