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
 * #110（docs/17 §6）：pi-messages 线格的 {@code contentIndex} 安全门。
 * 对位 pi {@code pi-messages.ts:195-205}：{@code _start} 可等于当前块数（追加新块），
 * 其他事件必须落在已有块内；非整数、负数、越界、缺键全部抛错并转成 {@code StreamError}。
 */
class PiMessagesContentIndexTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void startAtIndexZeroOnAnEmptyStreamIsAllowed() throws Exception {
        var events = collect(sse(
            event("{\"type\":\"start\"}"),
            event("{\"type\":\"text_start\",\"contentIndex\":0}"),
            event("{\"type\":\"text_end\",\"contentIndex\":0,\"content\":\"\"}"),
            done()));

        assertThat(events).anyMatch(e -> e instanceof StreamEvent.TextStart);
        assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamDone);
    }

    @Test
    void startBeyondTheNextSlotIsRejected() throws Exception {
        var error = errorOf(collect(sse(
            event("{\"type\":\"start\"}"),
            event("{\"type\":\"text_start\",\"contentIndex\":1}"),
            done())));

        assertThat(error.getMessage())
            .isEqualTo("Invalid contentIndex 1 in pi-messages text_start event");
    }

    @Test
    void deltaInsideTheExistingBlockIsAllowed() throws Exception {
        var events = collect(sse(
            event("{\"type\":\"start\"}"),
            event("{\"type\":\"text_start\",\"contentIndex\":0}"),
            event("{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"hi\"}"),
            event("{\"type\":\"text_end\",\"contentIndex\":0,\"content\":\"hi\"}"),
            done()));

        assertThat(events).anyMatch(e -> e instanceof StreamEvent.TextDelta);
        assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamDone);
    }

    @Test
    void deltaBeyondTheExistingBlockIsRejected() throws Exception {
        var error = errorOf(collect(sse(
            event("{\"type\":\"start\"}"),
            event("{\"type\":\"text_start\",\"contentIndex\":0}"),
            event("{\"type\":\"text_delta\",\"contentIndex\":1,\"delta\":\"hi\"}"),
            done())));

        assertThat(error.getMessage())
            .isEqualTo("Invalid contentIndex 1 in pi-messages text_delta event");
    }

    @Test
    void negativeIndexIsRejected() throws Exception {
        var error = errorOf(collect(sse(
            event("{\"type\":\"start\"}"),
            event("{\"type\":\"text_start\",\"contentIndex\":0}"),
            event("{\"type\":\"text_delta\",\"contentIndex\":-1,\"delta\":\"hi\"}"),
            done())));

        assertThat(error.getMessage())
            .isEqualTo("Invalid contentIndex -1 in pi-messages text_delta event");
    }

    @Test
    void stringIndexIsRejectedWithItsJsonLiteral() throws Exception {
        var error = errorOf(collect(sse(
            event("{\"type\":\"start\"}"),
            event("{\"type\":\"text_start\",\"contentIndex\":\"0\"}"),
            done())));

        assertThat(error.getMessage())
            .isEqualTo("Invalid contentIndex \"0\" in pi-messages text_start event");
    }

    @Test
    void missingIndexIsRejectedAsNull() throws Exception {
        var error = errorOf(collect(sse(
            event("{\"type\":\"start\"}"),
            event("{\"type\":\"text_start\",\"contentIndex\":0}"),
            event("{\"type\":\"text_delta\",\"delta\":\"hi\"}"),
            done())));

        assertThat(error.getMessage())
            .isEqualTo("Invalid contentIndex null in pi-messages text_delta event");
    }

    @Test
    void terminalEventsWithoutContentIndexAreNotValidated() throws Exception {
        var doneEvents = collect(sse(
            event("{\"type\":\"start\"}"),
            done()));
        assertThat(doneEvents).anyMatch(e -> e instanceof StreamEvent.StreamDone);

        tearDown();
        var errorEvents = collect(sse(
            event("{\"type\":\"start\"}"),
            event("{\"type\":\"error\",\"reason\":\"error\",\"usage\":null,"
                + "\"errorMessage\":\"gateway failed\",\"responseId\":\"r\"}")));
        assertThat(errorEvents).anyMatch(e -> e instanceof StreamEvent.StreamError);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private List<StreamEvent> collect(String sse) throws IOException {
        var baseUrl = startServer(sse);
        var api = new PiMessagesApi(
            new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(10), 0, Map.of()),
            "PI_MESSAGES_API_KEY");
        var events = new ArrayList<StreamEvent>();
        var request = StreamRequest.of(ModelId.of("pi-messages", "gateway-model"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))));
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    private static Throwable errorOf(List<StreamEvent> events) {
        return events.stream()
            .filter(StreamEvent.StreamError.class::isInstance)
            .map(e -> ((StreamEvent.StreamError) e).error())
            .findFirst().orElseThrow();
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

    private static String sse(String... events) {
        return String.join("", events) + "data: [DONE]\n\n";
    }

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String done() {
        return event("{\"type\":\"done\",\"reason\":\"stop\",\"usage\":null,"
            + "\"responseId\":\"resp\"}");
    }
}
