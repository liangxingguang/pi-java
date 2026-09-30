package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.http.ProviderRetry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-14：Mistral 经 PiHttpClient/RetryPolicy 的 provider 重试在真 HTTP 线上的
 * 形状：{@code x-should-retry} 短路、{@code retry-after-ms} 任意状态读取、
 * 60s cap、次数默认 0。
 */
class MistralProviderRetryWireTest {

    private static final String RATE_LIMIT_ERROR =
            "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\"}}";

    private static final byte[] SUCCESS_SSE = String.join("",
            "data: {\"choices\":[{\"index\":0,\"delta\":{},",
            "\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);

    private static StreamRequest request() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return StreamRequest.of(ModelId.of("mistral", "mistral-large-latest"), List.of(user));
    }

    private static ApiOptions options(String baseUrl, int maxRetries, Long capMs) {
        var extra = new java.util.LinkedHashMap<String, Object>();
        if (capMs != null) {
            extra.put("maxRetryDelayMs", capMs);
        }
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), maxRetries, extra);
    }

    private static List<StreamEvent> runStream(ApiOptions options) {
        var api = new MistralConversationsApi(options);
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
    void retriesOnceOnRetryAfterMsAndReturnsTheFreshStream() throws Exception {
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
                            "Retry-After", "2"),
                    RATE_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));

            var events = runStream(options(server.baseUrl(), 1, 1000L));

            assertThat(server.requestCount()).isEqualTo(1);
            var error = events.stream()
                    .filter(e -> e instanceof StreamEvent.StreamError)
                    .map(e -> ((StreamEvent.StreamError) e).error())
                    .findFirst().orElseThrow();
            assertThat(error).isInstanceOf(ProviderRetry.RetryDelayTooLongException.class)
                    .hasMessage("Server requested 2s retry delay (max: 1s). slow down");
        }
    }
}
