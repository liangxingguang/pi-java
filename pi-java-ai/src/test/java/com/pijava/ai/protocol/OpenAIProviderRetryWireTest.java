package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * A-14：OpenAI completions 车道的 provider 重试在真 HTTP 线上的形状
 * （pi {@code provider-retry.test.ts} 的 wire 对应面）。
 */
class OpenAIProviderRetryWireTest {

    private static final String RATE_LIMIT_ERROR =
            "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\"}}";

    private static final byte[] SUCCESS_SSE = String.join("\n",
            "data: {\"id\":\"a\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"a\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]", "", "").getBytes(StandardCharsets.UTF_8);

    private static ModelInfo model() {
        return ModelInfo.minimal(ModelId.of("openai", "gpt-4o"));
    }

    private static StreamRequest request() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return new StreamRequest(model(), "", List.of(user), List.of(), -1, -1, Map.of());
    }

    private static ApiOptions options(String baseUrl, Integer maxRetries, Long capMs) {
        var extra = new java.util.LinkedHashMap<String, Object>();
        if (capMs != null) {
            extra.put("maxRetryDelayMs", capMs);
        }
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5),
                maxRetries == null ? 0 : maxRetries, extra);
    }

    /** 跑完整条流，返回事件序列（错误也收成事件，不抛出）。 */
    private static List<StreamEvent> runStream(ApiOptions options) {
        var api = new OpenAICompletionsApi(options, "OPENAI_API_KEY");
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

            var events = runStream(options(server.baseUrl(), null, null));

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
                    // SDK 把状态码前缀拼进 message；pi 侧同样以 SDK error.message 为后缀，
                    // 判据＝provider message 逐字保留（不是裸 body 文案）。
                    .hasMessage("Server requested 2s retry delay (max: 1s). 429: slow down");
        }
    }
}
