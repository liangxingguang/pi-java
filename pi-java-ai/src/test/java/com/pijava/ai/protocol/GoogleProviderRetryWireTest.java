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
 * A-14：Google 车道的 provider 重试在真 HTTP 线上的形状。
 *
 * <p>genai 1.72 内置 RetryInterceptor（默认 2 attempts、退避不可见），车道
 * 以 {@code HttpRetryOptions.attempts(1)} 关掉；SDK 异常不携带 headers
 * （pi 补 {@code headers = undefined}），故 ProviderRetry 在本车道只走指数退避。</p>
 */
class GoogleProviderRetryWireTest {

    private static final String RATE_LIMIT_ERROR =
            "{\"error\":{\"code\":429,\"message\":\"slow down\","
                + "\"status\":\"RESOURCE_EXHAUSTED\"}}";

    private static String sseData(String candidateFields) {
        return "data: {\"candidates\":[{" + candidateFields + "}],"
                + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1}}\n\n";
    }

    private static final byte[] SUCCESS_SSE = (
            sseData("\"content\":{\"parts\":[{\"text\":\"hi\"}],\"role\":\"model\"}")
            + sseData("\"finishReason\":\"STOP\"")).getBytes(StandardCharsets.UTF_8);

    private static StreamRequest request() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return StreamRequest.of(ModelId.of("google", "gemini-2.5-flash"), List.of(user));
    }

    private static ApiOptions options(String baseUrl, int maxRetries) {
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5),
                maxRetries, Map.of());
    }

    private static List<StreamEvent> runStream(ApiOptions options) {
        var api = new GoogleGenerativeAiApi(options);
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

            var events = runStream(options(server.baseUrl(), 0));

            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        }
    }

    @Test
    void retriesOnceWithExponentialBackoffAndReturnsTheFreshStream() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    429, Map.of("Content-Type", "application/json"),
                    RATE_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));

            long started = System.nanoTime();
            var events = runStream(options(server.baseUrl(), 1));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(events).anyMatch(
                    e -> e instanceof StreamEvent.StreamDone d && "stop".equals(d.reason()));
            // pi: retryIndex 0 ⇒ 0.5s * (1 - rand*0.25) ∈ [375,500] ms。
            assertThat(elapsedMs).isBetween(350L, 5_000L);
        }
    }

    @Test
    void sdkBuiltInRetryIsDisabledEvenWhenProviderRetriesAreOff() throws Exception {
        // RE-3 对应面：漏掉 attempts(1) ⇒ 429 后 SDK 自行发第二次（退避后）。
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    429, Map.of("Content-Type", "application/json"),
                    RATE_LIMIT_ERROR.getBytes(StandardCharsets.UTF_8)));

            runStream(options(server.baseUrl(), 0));

            assertThat(server.requestCount())
                    .as("genai RetryInterceptor must not retry invisibly")
                    .isEqualTo(1);
        }
    }
}
