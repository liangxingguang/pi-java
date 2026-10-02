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
 * <b>Batch F 步 4</b>（{@code docs/67}）：Responses reasoning 块的**采集**与
 * **Azure 回填**。
 *
 * <p>pi 在 {@code response.output_item.done} 把整个 reasoning item
 * {@code JSON.stringify} 落进 thinkingSignature（{@code openai-responses-shared.ts:686-697}）；
 * Azure 可能在 done 时不给 encrypted_content、只在终局 response.output 里给，
 * pi 在 finalizeResponse 从终局回填（{@code :533-549}）。</p>
 */
class ResponsesReasoningCaptureTest {

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
    void capturesReasoningItemAsSignature() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\","
                + "\"status\":\"in_progress\",\"summary\":[],\"content\":[]}}"),
            event("{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\","
                + "\"output_index\":0,\"delta\":\"Let me think\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + reasoningItem(false) + "}"),
            completed(reasoningItem(false))));

        var done = last(events, StreamEvent.StreamDone.class);
        var block = (ContentBlock.ThinkingContent) done.partial().content().get(0);
        assertThat(block.text()).isEqualTo("Let me think");
        var sig = block.signature();
        assertThat(sig).isNotEmpty();
        var stored = MAPPER.readTree(sig);
        assertThat(stored.path("type").asText()).isEqualTo("reasoning");
        assertThat(stored.path("id").asText()).isEqualTo("rs_1");
        assertThat(stored.path("encrypted_content").asText()).isEqualTo("ENC-1234");
    }

    /**
     * Azure：done item 无 encrypted_content，终局 response.output 的 item 有
     * ⇒ 持久化签名必须回填该字段。
     */
    @Test
    void backfillsEncryptedContentFromTerminalOutput() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\","
                + "\"status\":\"in_progress\",\"summary\":[],\"content\":[]}}"),
            event("{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\","
                + "\"output_index\":0,\"delta\":\"hmm\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + reasoningItem(true) + "}"),
            completed(reasoningItem(false))));

        var done = last(events, StreamEvent.StreamDone.class);
        var block = (ContentBlock.ThinkingContent) done.partial().content().get(0);
        var stored = MAPPER.readTree(block.signature());
        assertThat(stored.path("encrypted_content").asText())
            .as("Azure 终局回填的 encrypted_content 必须落进签名")
            .isEqualTo("ENC-1234");
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    /** Reasoning item JSON；{@code noEncrypted} 时不带 encrypted_content（Azure done 形）。 */
    private static String reasoningItem(boolean noEncrypted) {
        return "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"completed\","
            + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"Let me think\"}]"
            + (noEncrypted ? "" : ",\"encrypted_content\":\"ENC-1234\"") + "}";
    }

    private List<StreamEvent> collect(String sseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        var api = new OpenAIResponsesApi(
            new ApiOptions("http://localhost:" + server.getAddress().getPort() + "/v1",
                "test-key", Duration.ofSeconds(5), 0, Map.of()),
            "OPENAI_API_KEY");
        var request = StreamRequest.of(ModelId.of("openai", "gpt-4o"),
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

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String sse(String... events) {
        return String.join("", events) + "data: [DONE]\n\n";
    }

    private static String created() {
        return event("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\","
            + "\"status\":\"in_progress\",\"model\":\"gpt-4o\",\"output\":[],"
            + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null}}");
    }

    private static String completed(String outputItem) {
        return event("{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
            + "\"status\":\"completed\",\"model\":\"gpt-4o\",\"output\":[" + outputItem + "],"
            + "\"parallel_tool_calls\":true,\"tools\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2,"
            + "\"input_tokens_details\":{\"cached_tokens\":0},"
            + "\"output_tokens_details\":{\"reasoning_tokens\":0}}}}");
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast)
            .reduce((a, b) -> b).orElseThrow();
    }
}
