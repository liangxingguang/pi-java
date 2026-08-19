package com.pijava.ai.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-1g: PiMessagesApi — {@code PiMessagesEvent} ↔ JSON round-trip、SSE 分帧、
 * 事件映射、缺终止事件抛错。
 */
class PiMessagesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void allEventVariantsRoundTrip() throws Exception {
        List<PiMessagesEvent> events = List.of(
            new PiMessagesEvent.Start(),
            new PiMessagesEvent.TextStart(0),
            new PiMessagesEvent.TextDelta(0, "hi"),
            new PiMessagesEvent.TextEnd(0, "hi", "sig"),
            new PiMessagesEvent.ThinkingStart(1),
            new PiMessagesEvent.ThinkingDelta(1, "think"),
            new PiMessagesEvent.ThinkingEnd(1, "think", "sig", true),
            new PiMessagesEvent.ToolCallStart(2, "call_1", "write"),
            new PiMessagesEvent.ToolCallDelta(2, "{\"path\":\""),
            new PiMessagesEvent.ToolCallEnd(2,
                new PiToolCall("call_1", "write", Map.of("path", "/tmp/x"))),
            new PiMessagesEvent.Done("stop", usage(), "resp_1", rewrite()),
            new PiMessagesEvent.Error("aborted", usage(), "boom", "resp_1", null));

        for (var event : events) {
            var json = JSON.writeValueAsString(event);
            var back = JSON.readValue(json, PiMessagesEvent.class);
            assertThat(back).isEqualTo(event);
        }
    }

    @Test
    void doneCarriesToolUseReasonMappedToToolUse() throws Exception {
        var baseUrl = startServer(toolCallSse());
        var api = api(baseUrl);
        var events = collect(api, "write");
        assertEventSequence(events, StreamEvent.Start.class,
            StreamEvent.ToolCallStart.class, StreamEvent.ToolCallDelta.class,
            StreamEvent.ToolCallDelta.class, StreamEvent.ToolCallEnd.class,
            StreamEvent.UsageInfo.class, StreamEvent.StreamDone.class);
        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo("tool_use");
        var end = last(events, StreamEvent.ToolCallEnd.class);
        assertThat(end.name()).isEqualTo("write");
        assertThat(end.arguments()).containsEntry("path", "/tmp/x");
    }

    @Test
    void textFlowMapsToTextEvents() throws Exception {
        var baseUrl = startServer(textSse());
        var api = api(baseUrl);
        var events = collect(api, "hi");
        assertEventSequence(events, StreamEvent.Start.class,
            StreamEvent.TextStart.class, StreamEvent.TextDelta.class,
            StreamEvent.TextDelta.class, StreamEvent.TextEnd.class,
            StreamEvent.UsageInfo.class, StreamEvent.StreamDone.class);
        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo("stop");
    }

    @Test
    void errorEventMapsToStreamError() throws Exception {
        var baseUrl = startServer(errorSse());
        var api = api(baseUrl);
        var events = collect(api, "hi");
        assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        var error = last(events, StreamEvent.StreamError.class);
        assertThat(error.reason()).isEqualTo("error");
    }

    @Test
    void streamWithoutTerminalEventMapsToError() throws Exception {
        var baseUrl = startServer(noTerminalSse());
        var api = api(baseUrl);
        var events = collect(api, "hi");
        assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
    }

    @Test
    void sseWithCrLfAndDoneIsHandled() throws Exception {
        // \r\n 行尾由 PiHttpClient 归一；[DONE] 被忽略。
        var baseUrl = startServer(crLfSse());
        var api = api(baseUrl);
        var events = collect(api, "hi");
        assertEventSequence(events, StreamEvent.Start.class,
            StreamEvent.TextStart.class, StreamEvent.TextDelta.class,
            StreamEvent.TextEnd.class, StreamEvent.StreamDone.class);
    }

    @Test
    void missingApiKeyThrows() {
        var options = new ApiOptions("http://localhost:1", "",
            Duration.ofSeconds(1), 0, Map.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new PiMessagesApi(options, "PI_MESSAGES_API_KEY"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PI_MESSAGES_API_KEY");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private PiMessagesApi api(String baseUrl) {
        return new PiMessagesApi(
            new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(10), 0, Map.of()),
            "PI_MESSAGES_API_KEY");
    }

    private List<StreamEvent> collect(PiMessagesApi api, String prompt) {
        var events = new ArrayList<StreamEvent>();
        var request = StreamRequest.of(ModelId.of("pi-messages", "gateway-model"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent(prompt)))));
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    private String startServer(String sseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/messages", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void assertEventSequence(List<StreamEvent> events, Class<?>... expected) {
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
            .containsExactly(java.util.Arrays.stream(expected)
                .map(Class::getSimpleName).toArray(String[]::new));
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance)
            .map(type::cast).reduce((a, b) -> b).orElseThrow();
    }

    private static Usage usage() {
        return new Usage(10, 5, 0, 0, null, null, 15, Usage.Cost.zero());
    }

    private static RewriteImpact rewrite() {
        return new RewriteImpact("pol-1", 1, true, -2, -1, false);
    }

    // ── SSE fixtures（pi-messages wire 格式）──────────────────────────────

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String textSse() {
        return event("{\"type\":\"start\"}")
            + event("{\"type\":\"text_start\",\"contentIndex\":0}")
            + event("{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"Hello\"}")
            + event("{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\" world\"}")
            + event("{\"type\":\"text_end\",\"contentIndex\":0,\"content\":\"Hello world\"}")
            + event("{\"type\":\"done\",\"reason\":\"stop\","
                + "\"usage\":{\"input\":10,\"output\":5,\"cacheRead\":0,\"cacheWrite\":0,"
                + "\"totalTokens\":15,\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,"
                + "\"cacheWrite\":0,\"total\":0}}}")
            + "data: [DONE]\n\n";
    }

    private static String toolCallSse() {
        return event("{\"type\":\"start\"}")
            + event("{\"type\":\"toolcall_start\",\"contentIndex\":0,"
                + "\"id\":\"call_1\",\"toolName\":\"write\"}")
            + event("{\"type\":\"toolcall_delta\",\"contentIndex\":0,"
                + "\"delta\":\"{\\\"path\\\":\\\"/tmp\"}")
            + event("{\"type\":\"toolcall_delta\",\"contentIndex\":0,"
                + "\"delta\":\"/x\\\"}\"}")
            + event("{\"type\":\"toolcall_end\",\"contentIndex\":0,"
                + "\"toolCall\":{\"id\":\"call_1\",\"name\":\"write\","
                + "\"arguments\":{\"path\":\"/tmp/x\"}}}")
            + event("{\"type\":\"done\",\"reason\":\"toolUse\","
                + "\"usage\":{\"input\":8,\"output\":4,\"cacheRead\":0,\"cacheWrite\":0,"
                + "\"totalTokens\":12,\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,"
                + "\"cacheWrite\":0,\"total\":0}}}")
            + "data: [DONE]\n\n";
    }

    private static String errorSse() {
        return event("{\"type\":\"start\"}")
            + event("{\"type\":\"error\",\"reason\":\"error\","
                + "\"usage\":null,\"errorMessage\":\"gateway failed\",\"responseId\":\"r\"}");
    }

    private static String noTerminalSse() {
        return event("{\"type\":\"start\"}")
            + event("{\"type\":\"text_start\",\"contentIndex\":0}")
            + event("{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"Hi\"}")
            + "data: [DONE]\n\n";
    }

    private static String crLfSse() {
        return "data: {\"type\":\"start\"}\r\n"
            + "\r\n"
            + "data: {\"type\":\"text_start\",\"contentIndex\":0}\r\n"
            + "\r\n"
            + "data: {\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"Hi\"}\r\n"
            + "\r\n"
            + "data: {\"type\":\"text_end\",\"contentIndex\":0,\"content\":\"Hi\"}\r\n"
            + "\r\n"
            + "data: {\"type\":\"done\",\"reason\":\"stop\",\"usage\":null}\r\n"
            + "\r\n";
    }
}
