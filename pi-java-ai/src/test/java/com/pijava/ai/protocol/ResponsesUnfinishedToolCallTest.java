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
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * B118（批1，原 docs/09 ⑤）：pi {@code openai-responses-shared.ts:764-775} 的终局守卫——
 * {@code output_item.done} 永不到达的工具调用（scratch 槽仍在）在 {@code stopReason:"toolUse"}
 * 时必须判 provider error，不能把残缺/串位的参数当真工具调用执行。
 */
class ResponsesUnfinishedToolCallTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String functionCallItem(String status, String arguments) {
        return "{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\","
            + "\"name\":\"ls\",\"status\":\"" + status + "\""
            + (arguments == null ? "" : ",\"arguments\":" + arguments) + "}";
    }

    @Test
    void unfinishedFunctionCallBecomesStreamErrorWithPiWording() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                + functionCallItem("in_progress", null) + "}"),
            event("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"delta\":\"{\\\"path\\\":\"}"),
            // 故意不发 response.output_item.done —— output_item.done 永不到达。
            completed(functionCallItem("in_progress", "\"{\\\"path\\\":\\\"partial\\\"}\""))));

        var errors = events.stream().filter(StreamEvent.StreamError.class::isInstance).toList();
        assertThat(errors).as("恰好一个 StreamError 终局").hasSize(1);
        assertThat(events).as("守卫抛错后不许再发 done").noneMatch(StreamEvent.StreamDone.class::isInstance);
        assertThat(((StreamEvent.StreamError) errors.get(0)).error().getMessage())
            .isEqualTo("OpenAI Responses stream completed with an unfinished tool call: "
                + "ls (call_1|fc_1)");
    }

    @Test
    void finishedCallCompletesNormally() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                + functionCallItem("in_progress", null) + "}"),
            event("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"delta\":\"{\\\"path\\\":\"}"),
            event("{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"arguments\":\"{\\\"path\\\":\\\"docs\\\"}\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
                + functionCallItem("completed", "\"{\\\"path\\\":\\\"docs\\\"}\"") + "}"),
            completed(functionCallItem("completed", "\"{\\\"path\\\":\\\"docs\\\"}\""))));

        assertThat(events).as("done 已清槽 ⇒ 守卫不触发").noneMatch(StreamEvent.StreamError.class::isInstance);
        var done = events.stream()
            .filter(StreamEvent.StreamDone.class::isInstance)
            .map(StreamEvent.StreamDone.class::cast)
            .reduce((a, b) -> b).orElseThrow();
        assertThat(done.reason()).isEqualTo(StopReason.TOOL_USE);
        var toolCall = (ContentBlock.ToolUseContent) done.partial().content().get(0);
        assertThat(toolCall.name()).isEqualTo("ls");
        assertThat(toolCall.arguments()).isEqualTo(Map.of("path", "docs"));
    }

    @Test
    void stopWithoutToolUseDoesNotTriggerGuard() throws Exception {
        // 残留槽与 stop:"stop" 组合在协议上不该出现，但守卫只在 toolUse 时开火（同 pi 条件）。
        var messageItem = "{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\","
            + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\",\"annotations\":[]}]}";
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                + messageItem + "}"),
            event("{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"content_index\":0,\"delta\":\"hi\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
                + messageItem + "}"),
            completed(messageItem)));

        assertThat(events).noneMatch(StreamEvent.StreamError.class::isInstance);
        assertThat(events).anyMatch(StreamEvent.StreamDone.class::isInstance);
    }

    // ── 夹具（同 ResponsesFunctionCallMissingArgumentsTest）──────────────

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
}
