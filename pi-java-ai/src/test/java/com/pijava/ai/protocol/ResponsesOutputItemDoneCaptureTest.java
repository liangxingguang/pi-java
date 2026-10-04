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
 * <b>原 docs/71 G3/G4</b>：Responses {@code response.output_item.done} 的收尾合并 —— 逐行照 pi
 * {@code openai-responses-shared.ts:699-708}（文本 ＋ refusal 的权威内容）与
 * {@code :442-446}（{@code phase === "final_answer"} ⇒ {@code stopReason = "stop"}）。
 *
 * <p>观察面：{@code text_end} 事件的 {@code partial}。⚠️ 最终 {@code done} 事件的 reason
 * 由**终局事件**的映射覆盖（pi {@code :590} 亦然）⇒ phase 那一支只在 text_end 上可观察。</p>
 */
class ResponsesOutputItemDoneCaptureTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── G3：回执签名 ────────────────────────────────────────────────────

    @Test
    void doneItemWritesTheTextSignature() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "in_progress", "[]", null) + "}"),
            event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hel\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "completed", textContent("Hello"), null) + "}"),
            completed(messageItem("msg_1", "completed", textContent("Hello"), null))));

        var block = (ContentBlock.TextContent) last(events, StreamEvent.StreamDone.class)
            .partial().content().get(0);
        assertThat(block.textSignature()).isEqualTo(TextSignatureV1.encode("msg_1", null));
    }

    @Test
    void doneItemReplaysThePhase() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "in_progress", "[]", null) + "}"),
            event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hi\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "completed", textContent("Hi"), "final_answer") + "}"),
            completed(messageItem("msg_1", "completed", textContent("Hi"), "final_answer"))));

        var block = (ContentBlock.TextContent) last(events, StreamEvent.StreamDone.class)
            .partial().content().get(0);
        assertThat(block.textSignature())
            .isEqualTo(TextSignatureV1.encode("msg_1", "final_answer"));
    }

    // ── G3：refusal 的权威内容合并 ──────────────────────────────────────

    /** 收尾 content 与 delta 之和不一致 ⇒ 取**收尾**的（pi {@code :700} 覆盖）。 */
    @Test
    void doneContentOverridesAccumulatedDeltas() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "in_progress", "[]", null) + "}"),
            event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"partial\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "completed", textContent("authoritative"), null) + "}"),
            completed(messageItem("msg_1", "completed", textContent("authoritative"), null))));

        assertThat(textOfLast(events)).isEqualTo("authoritative");
    }

    /** refusal **只**在收尾内容里给（没有流式 delta）⇒ 也必须上线。 */
    @Test
    void refusalDeliveredOnlyInDoneContentSurvives() throws Exception {
        var refusal = "[{\"type\":\"refusal\",\"refusal\":\"I cannot do that\"}]";

        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "in_progress", "[]", null) + "}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "completed", refusal, null) + "}"),
            completed(messageItem("msg_1", "completed", refusal, null))));

        assertThat(textOfLast(events)).isEqualTo("I cannot do that");
    }

    /** 流式 refusal delta ＋ 收尾内容一致 ⇒ 结果就是那段文本（两路不重复、不丢）。 */
    @Test
    void refusalDeltaAndDoneContentAgree() throws Exception {
        var refusal = "[{\"type\":\"refusal\",\"refusal\":\"no\"}]";

        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "in_progress", "[]", null) + "}"),
            event("{\"type\":\"response.refusal.delta\",\"output_index\":0,\"delta\":\"no\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "completed", refusal, null) + "}"),
            completed(messageItem("msg_1", "completed", refusal, null))));

        assertThat(textOfLast(events)).isEqualTo("no");
    }

    /**
     * R4 的刻意偏差：收尾 content **为空** ⇒ 不覆盖（否则会把已流出的文本清空）。
     * pi 是无条件覆盖 —— 这一支登记为偏差（原 docs/71 §4.2）。
     */
    @Test
    void emptyDoneContentKeepsTheAccumulatedText() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "in_progress", "[]", null) + "}"),
            event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"kept\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "completed", "[]", null) + "}"),
            completed(messageItem("msg_1", "completed", "[]", null))));

        assertThat(textOfLast(events)).isEqualTo("kept");
    }

    // ── G4：phase ⇒ stopReason（只在 text_end 的 partial 上可观察）───────

    @Test
    void finalAnswerPhaseSetsStopOnTheTextEndPartial() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "in_progress", "[]", null) + "}"),
            event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hi\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "completed", textContent("Hi"), "final_answer") + "}"),
            completed(messageItem("msg_1", "completed", textContent("Hi"), "final_answer"))));

        var textEnd = last(events, StreamEvent.TextEnd.class);
        assertThat(textEnd.partial().stopReason()).isEqualTo("stop");
    }

    /** 对照：没有 phase ⇒ text_end 的 partial 还是待定（这一支才有判别力）。 */
    @Test
    void withoutPhaseStopStaysPendingOnTheTextEndPartial() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "in_progress", "[]", null) + "}"),
            event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hi\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + messageItem("msg_1", "completed", textContent("Hi"), "commentary") + "}"),
            completed(messageItem("msg_1", "completed", textContent("Hi"), "commentary"))));

        var textEnd = last(events, StreamEvent.TextEnd.class);
        assertThat(textEnd.partial().stopReason()).isNotEqualTo("stop");
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    private static String textOfLast(List<StreamEvent> events) {
        var content = last(events, StreamEvent.StreamDone.class).partial().content();
        return ((ContentBlock.TextContent) content.get(0)).text();
    }

    /** 一条 assistant message item 的线格形状（id 是 SDK 的必填项）。 */
    private static String messageItem(String id, String status, String content, String phase) {
        return "{\"id\":\"" + id + "\",\"type\":\"message\",\"status\":\"" + status + "\","
            + "\"role\":\"assistant\",\"content\":" + content
            + (phase == null ? "" : ",\"phase\":\"" + phase + "\"") + "}";
    }

    private static String textContent(String text) {
        return "[{\"type\":\"output_text\",\"text\":\"" + text + "\",\"annotations\":[]}]";
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
