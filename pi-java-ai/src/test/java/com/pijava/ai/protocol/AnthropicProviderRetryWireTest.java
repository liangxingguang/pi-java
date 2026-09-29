package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.http.ProviderRetry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-14：Anthropic 车道的 provider 重试在真 HTTP 线上的形状
 * （与 {@link OpenAIProviderRetryWireTest} 同构）。
 */
class AnthropicProviderRetryWireTest {

    private static final String RATE_LIMIT_ERROR =
            "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}";

    private static byte[] sseEvent(String event, String data) {
        return ("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] SUCCESS_SSE = concat(
            sseEvent("message_start",
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}"),
            sseEvent("content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
            sseEvent("content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}"),
            sseEvent("content_block_stop",
                    "{\"type\":\"content_block_stop\",\"index\":0}"),
            sseEvent("message_delta",
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}"),
            sseEvent("message_stop", "{\"type\":\"message_stop\"}"));

    private static byte[] concat(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static ModelInfo model() {
        return ModelInfo.minimal(ModelId.of("anthropic", "claude-sonnet-5"));
    }

    private static StreamRequest request() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return new StreamRequest(model(), "", List.of(user), List.of(), -1, -1, Map.of());
    }

    private static ApiOptions options(String baseUrl, int maxRetries, Long capMs) {
        var extra = new java.util.LinkedHashMap<String, Object>();
        if (capMs != null) {
            extra.put("maxRetryDelayMs", capMs);
        }
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), maxRetries, extra);
    }

    private static List<StreamEvent> runStream(ApiOptions options) {
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        var events = new java.util.ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        } catch (Exception e) {
            throw new AssertionError("stream must surface failures as events", e);
        }
        return events;
    }

    @Test
    void defaultsToZeroRetriesOn429() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    429, Map.of("Content-Type", "application/json"),
                    RATE_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));

            var events = runStream(options(server.baseUrl(), 0, null));

            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        }
    }

    @Test
    void retriesOnceAndReturnsTheFreshStream() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    429, Map.of("Content-Type", "application/json",
                            "retry-after-ms", "0"),
                    RATE_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));

            var events = runStream(options(server.baseUrl(), 1, null));

            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(events).anyMatch(
                    e -> e instanceof StreamEvent.StreamDone d && "stop".equals(d.reason()));
        }
    }

    @Test
    void respectsShouldRetryFalseEvenWhenRetriesAreEnabled() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    429, Map.of("Content-Type", "application/json",
                            "x-should-retry", "false"),
                    RATE_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));

            var events = runStream(options(server.baseUrl(), 2, null));

            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        }
    }

    @Test
    void failsImmediatelyWhenServerDelayExceedsTheCap() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    429, Map.of("Content-Type", "application/json",
                            "retry-after", "2"),
                    RATE_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));

            var events = runStream(options(server.baseUrl(), 1, 1000L));

            assertThat(server.requestCount()).isEqualTo(1);
            var error = events.stream()
                    .filter(e -> e instanceof StreamEvent.StreamError)
                    .map(e -> ((StreamEvent.StreamError) e).error())
                    .findFirst().orElseThrow();
            assertThat(error).isInstanceOf(ProviderRetry.RetryDelayTooLongException.class)
                    .hasMessageContaining("Server requested 2s retry delay (max: 1s).");
        }
    }
}
