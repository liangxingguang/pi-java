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
 * <b>docs/05 B152 的兜底</b>：端点把**同一个** function_call item 发两次时，只保留一条。
 *
 * <p>复现自真实端点：失败转录里两个 {@code tool_use} 的 {@code call_id}、{@code item.id}
 * 与参数**逐字相同**（{@code fc_} 是端点分配的 ⇒ 同一个 item 被发了两遍）。pi 会照单全收
 * （每个 output_index 推一个块），回放时于是发出两条同 {@code call_id} 的
 * {@code function_call} ＋ 两条同 {@code call_id} 的 {@code function_call_output}，
 * 中转（TeamoRouter→DeepSeek）以 400 拒绝（二分实证：只把重复那个的 id 改成唯一即恢复）。</p>
 *
 * <p>去重判据是 D3 的**复合 id**（{@code call_id|item.id}）逐字相等 —— 合法的重复调用
 * 会拿到**不同**的 call_id，故这条判据不会误伤。</p>
 */
class ResponsesDuplicateToolCallTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String item(int index, String status) {
        return "{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\","
            + "\"name\":\"ls\",\"status\":\"" + status + "\","
            + "\"arguments\":\"{\\\"path\\\":\\\"docs\\\"}\"}";
    }

    @Test
    void duplicatedAddedItemYieldsOneToolCall() throws Exception {
        var events = collect(sse(
            created(),
            // 同一个 item 在两个 output_index 上各来一次 —— 端点重复投递的形状。
            event(added(0, item(0, "in_progress"))),
            event(added(1, item(1, "in_progress"))),
            event("{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"arguments\":\"{\\\"path\\\":\\\"docs\\\"}\"}"),
            event(done(0, item(0, "completed"))),
            event(done(1, item(1, "completed"))),
            completed(item(0, "completed"))));

        assertThat(events).noneMatch(StreamEvent.StreamError.class::isInstance);

        var content = last(events, StreamEvent.StreamDone.class).partial().content();
        assertThat(content.stream().filter(ContentBlock.ToolUseContent.class::isInstance))
            .as("重复投递的同一个 item ⇒ 只留一条 tool call（否则回放会被 400 拒）")
            .hasSize(1);
        var toolCall = (ContentBlock.ToolUseContent) content.get(0);
        assertThat(toolCall.id()).isEqualTo("call_1|fc_1");
        assertThat(toolCall.arguments()).isEqualTo(Map.of("path", "docs"));
    }

    /** 对照：两个 **不同** 的调用（不同 call_id/item.id）必须原样保留两条。 */
    @Test
    void distinctToolCallsAreBothKept() throws Exception {
        var second = "{\"id\":\"fc_2\",\"type\":\"function_call\",\"call_id\":\"call_2\","
            + "\"name\":\"ls\",\"status\":\"completed\","
            + "\"arguments\":\"{\\\"path\\\":\\\"docs\\\"}\"}";
        var events = collect(sse(
            created(),
            event(added(0, item(0, "in_progress"))),
            event(added(1, second)),
            event(done(0, item(0, "completed"))),
            event(done(1, second)),
            completed(item(0, "completed"))));

        var content = last(events, StreamEvent.StreamDone.class).partial().content();
        assertThat(content.stream().filter(ContentBlock.ToolUseContent.class::isInstance)).hasSize(2);
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    private static String added(int index, String item) {
        return "{\"type\":\"response.output_item.added\",\"output_index\":" + index
            + ",\"item\":" + item + "}";
    }

    private static String done(int index, String item) {
        return "{\"type\":\"response.output_item.done\",\"output_index\":" + index
            + ",\"item\":" + item + "}";
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
