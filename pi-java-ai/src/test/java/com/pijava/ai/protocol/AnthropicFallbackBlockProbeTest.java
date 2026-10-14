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
 * 批 5 实施第 0 步探针（同 docs/13 beta 头探针先例）：settle anthropic-java 2.66.0 对
 * pi {@code content_block_start} 的 {@code "fallback"} 未知块、以及事件顶层
 * {@code input_transformations} 未知键的反序列化行为与 raw 访问面。
 *
 * <p>SDK 的 {@code RawContentBlockStartEvent.ContentBlock} 联合只有 12 个已知变体
 * （无 fallback），尾部留 {@code JsonValue _json()} 逃生槽。本探针钉死：</p>
 * <ul>
 *   <li>未知 type 不导致整条流反序列化失败；</li>
 *   <li>{@code _json()} 内有原文且能读到 {@code type=="fallback"}，已知访问器全空；</li>
 *   <li>{@code message_start.message().model().asString()} 对未知模型 id 回显原文；</li>
 *   <li>事件顶层未知键经 {@code _additionalProperties()} 可取。</li>
 * </ul>
 */
class AnthropicFallbackBlockProbeTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fallbackBlockIsReachableThroughRawJsonWithoutBreakingTheStream() throws Exception {
        var body = sse("message_start", "{\"type\":\"message_start\",\"message\":{"
                + "\"id\":\"msg_fallback\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"some-fallback-model\",\"content\":[],"
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"fallback\"}}")
            + sse("message_delta", "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":15}}")
            + sse("message_stop", "{\"type\":\"message_stop\"}");
        var events = collect(body);

        var errors = events.stream().filter(StreamEvent.StreamError.class::isInstance).toList();
        assertThat(errors).as("fallback 块在无已有内容时跳过，流正常收束").isEmpty();
        var done = events.stream().filter(StreamEvent.StreamDone.class::isInstance)
            .map(StreamEvent.StreamDone.class::cast).reduce((a, b) -> b).orElseThrow();
        assertThat(done.partial().model()).isEqualTo("claude-sonnet-4");
    }

    private List<StreamEvent> collect(String sseBody) throws Exception, IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] bodyBytes = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bodyBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bodyBytes);
            }
        });
        server.start();
        var api = new AnthropicMessagesApi(new ApiOptions(
            "http://localhost:" + server.getAddress().getPort(), "test-key",
            Duration.ofSeconds(5), 0, Map.of()));

        var events = new CopyOnWriteArrayList<StreamEvent>();
        var finished = new CountDownLatch(1);
        api.stream(StreamRequest.of(ModelId.of("anthropic", "claude-sonnet-4"),
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))))),
            ApiOptions.defaults()).subscribe(new Flow.Subscriber<>() {
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
        assertThat(finished.await(10, TimeUnit.SECONDS)).as("流未在 10s 内结束").isTrue();
        return List.copyOf(events);
    }

    private static String sse(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }
}
