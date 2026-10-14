package com.pijava.ai.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

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
 * B148：openai-completions 车道的 responseModel（对位 pi
 * {@code openai-completions-response-model.test.ts}，:565-567）：
 * 首个非空且与请求 id 不同的 {@code chunk.model} 落消息（首值胜，{@code ||=}）；
 * 回显同 id / 空串 / 缺键 ⇒ 无该键。
 */
class CompletionsResponseModelWireTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void routedModelLandsOnResponseModelWithoutChangingModel() throws Exception {
        String sse = chunk("anthropic/claude-opus-4.8", "{\"content\":\"hi\"}", null)
            + chunk("anthropic/claude-opus-4.8", "{}", "stop");
        var events = collect(sse);

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.partial().model()).isEqualTo("openrouter/auto");
        assertThat(done.partial().responseModel()).isEqualTo("anthropic/claude-opus-4.8");
    }

    @Test
    void echoedRequestIdLeavesResponseModelAbsent() throws Exception {
        String sse = chunk("openrouter/auto", "{\"content\":\"hi\"}", null)
            + chunk("openrouter/auto", "{}", "stop");
        var events = collect(sse);

        assertThat(last(events, StreamEvent.StreamDone.class).partial().responseModel())
            .isNull();
    }

    @Test
    void emptyOrMissingChunkModelIsIgnored() throws Exception {
        String sse = "data: {\"id\":\"c3\",\"model\":\"\",\"choices\":[{\"index\":0,"
            + "\"delta\":{\"content\":\"!\"}}]}\n\n"
            + "data: {\"id\":\"c3\",\"choices\":[{\"index\":0,"
            + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
        var events = collect(sse);

        assertThat(last(events, StreamEvent.StreamDone.class).partial().responseModel())
            .isNull();
    }

    @Test
    void firstDifferentModelWins() throws Exception {
        String sse = chunk("routed-a", "{\"content\":\"a\"}", null)
            + chunk("routed-b", "{\"content\":\"b\"}", null)
            + chunk("routed-b", "{}", "stop");
        var events = collect(sse);

        assertThat(last(events, StreamEvent.StreamDone.class).partial().responseModel())
            .isEqualTo("routed-a");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private List<StreamEvent> collect(String sseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bodyBytes = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bodyBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bodyBytes);
            }
        });
        server.start();
        var api = new OpenAICompletionsApi(new ApiOptions(
            "http://localhost:" + server.getAddress().getPort() + "/v1",
            "test-key", Duration.ofSeconds(5), 0, Map.of()));
        var request = StreamRequest.of(ModelId.of("openrouter", "openrouter/auto"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))));

        var events = new CopyOnWriteArrayList<StreamEvent>();
        var finished = new CountDownLatch(1);
        api.stream(request, ApiOptions.defaults()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(StreamEvent e) {
                events.add(e);
            }
            @Override public void onError(Throwable t) {
                finished.countDown();
            }
            @Override public void onComplete() {
                finished.countDown();
            }
        });
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        return List.copyOf(events);
    }

    private static String chunk(String model, String deltaJson, String finishReason) {
        String tail = finishReason == null ? "" : ",\"finish_reason\":\"" + finishReason + "\"";
        return "data: {\"id\":\"c1\",\"model\":\"" + model + "\",\"choices\":[{\"index\":0,"
            + "\"delta\":" + deltaJson + tail + "}]}\n\n";
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast)
            .reduce((a, b) -> b).orElseThrow();
    }
}
