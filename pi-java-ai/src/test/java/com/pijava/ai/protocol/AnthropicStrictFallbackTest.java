package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ConstrainedSampling;
import com.pijava.ai.api.JsonSchemaSampling;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.StrictMode;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #101（docs/17 §5）：Anthropic strict 工具的**一次性运行时降级**（pi
 * {@code anthropic-messages.ts:649-689}）。只有「prefer 工具 + 请求尚未开始流 +
 * 400 invalid_request_error + 三类工具上限文案」同时满足才降级，且只降一次。
 */
class AnthropicStrictFallbackTest {

    private static final String STRICT_LIMIT_ERROR =
        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"Too many strict tools (limit 20)\"}}";

    private static final byte[] SUCCESS_SSE = concat(
        sse("message_start",
            "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude\","
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}"),
        sse("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
        sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
        sse("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}"),
        sse("message_stop", "{\"type\":\"message_stop\"}"));

    @Test
    void rejectedPreferToolIsRetriedOnceWithoutStrictAndLeavesOneDiagnostic() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                400, Map.of("Content-Type", "application/json"),
                STRICT_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));
            server.enqueue(new RecordingHttpServer.Response(
                200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));

            var events = run(server.baseUrl(), tool(new JsonSchemaSampling(StrictMode.PREFER)),
                Map.of());

            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(server.bodies().get(0)).contains("\"strict\":true");
            assertThat(server.bodies().get(1)).doesNotContain("\"strict\":true");
            var done = last(events, StreamEvent.StreamDone.class);
            assertThat(done.partial().diagnostics()).hasSize(1);
            var diagnostic = done.partial().diagnostics().get(0);
            assertThat(diagnostic.type()).isEqualTo("anthropic_strict_tools_fallback");
            assertThat(diagnostic.error().message()).contains("Too many strict tools");
            assertThat(diagnostic.details().path("reason").asText())
                .isEqualTo("strict_tool_limits_exceeded");
            // 请求成功后才发 start（pi :692）。
            assertThat(events.stream().filter(e -> e instanceof StreamEvent.Start)).hasSize(1);
        }
    }

    @Test
    void requireToolsAreNeverDowngraded() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                400, Map.of("Content-Type", "application/json"),
                STRICT_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));

            var events = run(server.baseUrl(), tool(new JsonSchemaSampling(StrictMode.REQUIRE)),
                Map.of());

            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
            assertThat(events).noneMatch(e -> e instanceof StreamEvent.Start);
        }
    }

    @Test
    void plainToolsAreNeverDowngraded() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                400, Map.of("Content-Type", "application/json"),
                STRICT_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));

            var events = run(server.baseUrl(), tool(null), Map.of());

            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        }
    }

    @Test
    void unrelated400MessagesAreNotDowngraded() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                400, Map.of("Content-Type", "application/json"),
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                    .concat("\"message\":\"bad request\"}}")
                    .getBytes(StandardCharsets.UTF_8)));

            var events = run(server.baseUrl(), tool(new JsonSchemaSampling(StrictMode.PREFER)),
                Map.of());

            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        }
    }

    @Test
    void aSecondStrictLimitFailureIsNotDowngradedAgain() throws Exception {
        try (var server = new RecordingHttpServer()) {
            for (int i = 0; i < 2; i++) {
                server.enqueue(new RecordingHttpServer.Response(
                    400, Map.of("Content-Type", "application/json"),
                    STRICT_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));
            }

            var events = run(server.baseUrl(), tool(new JsonSchemaSampling(StrictMode.PREFER)),
                Map.of());

            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
            var error = last(events, StreamEvent.StreamError.class);
            assertThat(error.error().getMessage()).contains("Too many strict tools");
        }
    }

    @Test
    void anInterruptedThreadDoesNotDowngrade() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                400, Map.of("Content-Type", "application/json"),
                STRICT_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));
            ProviderHooks.PayloadHook interrupt = (payload, model) -> {
                Thread.currentThread().interrupt();
                return null;
            };

            var events = run(server.baseUrl(), tool(new JsonSchemaSampling(StrictMode.PREFER)),
                Map.of(ProviderHooks.ON_PAYLOAD, interrupt));

            // 中断的线程不降级；OkHttp 可能在发站前就失败，故只断言可观察面（无 start /
            // 有 StreamError），不绑定请求计数。
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
            assertThat(events).noneMatch(e -> e instanceof StreamEvent.Start);
        }
    }

    @Test
    void payloadHookRunsEveryAttemptButResponseHookOnlyOnSuccess() throws Exception {
        var payloads = new AtomicInteger();
        var responses = new AtomicInteger();
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                400, Map.of("Content-Type", "application/json"),
                STRICT_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));
            server.enqueue(new RecordingHttpServer.Response(
                200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));
            ProviderHooks.PayloadHook payloadHook = (payload, model) -> {
                payloads.incrementAndGet();
                return null;
            };
            ProviderHooks.ResponseHook responseHook = (status, headers, model) ->
                responses.incrementAndGet();

            run(server.baseUrl(), tool(new JsonSchemaSampling(StrictMode.PREFER)),
                Map.of(ProviderHooks.ON_PAYLOAD, payloadHook,
                    ProviderHooks.ON_RESPONSE, responseHook));

            assertThat(payloads).hasValue(2);
            assertThat(responses).hasValue(1);
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static List<StreamEvent> run(String baseUrl, ToolDefinition toolDef,
                                         Map<String, Object> extraHooks) {
        var api = new AnthropicMessagesApi(
            new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0, extraHooks),
            "ANTHROPIC_API_KEY");
        var messages = List.<Message>of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(toolDef), List.of()),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));
        var request = new StreamRequest(model(), null, messages, List.of(), -1, -1);
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    private static ModelInfo model() {
        return BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", "claude-sonnet-5")).orElseThrow();
    }

    private static ToolDefinition tool(ConstrainedSampling sampling) {
        return new ToolDefinition("t", "d",
            Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string"))),
            "t", null, List.of(), "default", sampling);
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance)
            .map(type::cast).reduce((a, b) -> b).orElseThrow();
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
