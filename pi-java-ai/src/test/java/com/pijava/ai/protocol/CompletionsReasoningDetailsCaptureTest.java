package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * <b>Batch F 步 6</b>（{@code 原 docs/67}）：Completions {@code reasoning_details}
 * 的**采集** —— pi 从 delta 的 reasoning_details 数组逐项校验合并，
 * 收尾把逻辑条目数组 JSON 落进 thinkingSignature
 * （{@code openai-completions.ts:664-675}，stamp :440）。
 */
class CompletionsReasoningDetailsCaptureTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void capturesEncryptedReasoningDetail() throws Exception {
        var events = collect(
            chunk("\"reasoning_details\":[{\"type\":\"reasoning.encrypted\","
                + "\"id\":\"rs_1\",\"data\":\"ENC-1234\"}]"),
            finishChunk());

        var done = last(events, StreamEvent.StreamDone.class);
        var block = (ContentBlock.ThinkingContent) done.partial().content().get(0);
        var stored = MAPPER.readTree(block.signature());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).path("type").asText()).isEqualTo("reasoning.encrypted");
        assertThat(stored.get(0).path("data").asText()).isEqualTo("ENC-1234");
    }

    /** Consecutive reasoning.text deltas merge into one logical entry. */
    @Test
    void mergesAdjacentTextDetails() throws Exception {
        var events = collect(
            chunk("\"reasoning_details\":[{\"type\":\"reasoning.text\","
                + "\"text\":\"abc\"}]"),
            chunk("\"reasoning_details\":[{\"type\":\"reasoning.text\","
                + "\"text\":\"def\"}]"),
            finishChunk());

        var done = last(events, StreamEvent.StreamDone.class);
        var block = (ContentBlock.ThinkingContent) done.partial().content().get(0);
        var stored = MAPPER.readTree(block.signature());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).path("text").asText())
            .as("相邻 reasoning.text 必须合并而非推两条")
            .isEqualTo("abcdef");
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    private List<StreamEvent> collect(String... dataLines) throws Exception {
        String sse = String.join("", dataLines) + "data: [DONE]\n\n";
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
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
        assertThat(finished.await(10, TimeUnit.SECONDS)).as("流未在 10s 内结束").isTrue();
        return List.copyOf(events);
    }

    private static String chunk(String deltaFields) {
        return "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,"
            + "\"delta\":{" + deltaFields + "}}]}\n\n";
    }

    private static String finishChunk() {
        return "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,"
            + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast)
            .reduce((a, b) -> b).orElseThrow();
    }
}
