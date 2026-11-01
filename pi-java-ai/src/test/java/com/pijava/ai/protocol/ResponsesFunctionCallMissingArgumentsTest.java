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
 * <b>回归</b>：{@code response.output_item.added} 的 {@code function_call} 项**不带
 * {@code arguments}** 时不许炸。
 *
 * <p>pi 在那一支读的是 {@code item.arguments || ""}（{@code openai-responses-shared.ts:485-490}）
 * —— TS 里缺键就是 {@code undefined}、落到空串。Java 侧若直接用 SDK 的必填访问器
 * {@code ResponseFunctionToolCall.arguments()}，SDK 的 {@code JsonField.getValue()} 会抛
 * {@code IllegalStateException("`arguments` is not set")}（2026-10-03 实测：TeamoRouter 的
 * {@code /responses} 在 added 事件里就不带这个键 ⇒ 整轮 0 token 断流）。</p>
 *
 * <p>同一个 {@code added} 项在 OpenAI 自己的线上是带 {@code "arguments": ""} 的，
 * 所以既有夹具（都带）永远撞不到这一支。</p>
 */
class ResponsesFunctionCallMissingArgumentsTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** added 项**没有** arguments 键 —— 缺口的复现条件。 */
    private static String functionCallItem(String status) {
        return "{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\","
            + "\"name\":\"ls\",\"status\":\"" + status + "\"}";
    }

    @Test
    void addedItemWithoutArgumentsStillStreamsTheToolCall() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + functionCallItem("in_progress") + "}"),
            event("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"delta\":\"{\\\"path\\\":\"}"),
            event("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"delta\":\"\\\"docs\\\"}\"}"),
            event("{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"arguments\":\"{\\\"path\\\":\\\"docs\\\"}\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + functionCallItem("completed") + "}"),
            completed(functionCallItem("completed"))));

        assertThat(events).as("流不许以错误收场").noneMatch(StreamEvent.StreamError.class::isInstance);
        assertThat(events).anyMatch(StreamEvent.StreamDone.class::isInstance);

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.partial().content()).hasSize(1);
        var toolCall = (ContentBlock.ToolUseContent) done.partial().content().get(0);
        assertThat(toolCall.name()).isEqualTo("ls");
        assertThat(toolCall.id()).isEqualTo("call_1|fc_1");
        assertThat(toolCall.arguments()).isEqualTo(Map.of("path", "docs"));
    }

    /**
     * <b>第二个同源缺口</b>：参数**只**在 {@code output_item.done} 里给（一个 delta 都没有）。
     *
     * <p>pi {@code :710} 在 done 支取的是 {@code item.arguments || partialJson || "{}"} —— 收尾项
     * 里的值是**权威**的。只靠 delta 累积的实现会静默拿到 {@code {}}（工具被空参调用）。</p>
     */
    @Test
    void argumentsDeliveredOnlyInTheDoneItemSurvive() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":" + functionCallItem("in_progress") + "}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":" + functionCallWithArguments() + "}"),
            completed(functionCallWithArguments())));

        assertThat(events).as("流不许以错误收场").noneMatch(StreamEvent.StreamError.class::isInstance);
        var toolCall = (ContentBlock.ToolUseContent) last(events, StreamEvent.StreamDone.class)
            .partial().content().get(0);
        assertThat(toolCall.arguments()).isEqualTo(Map.of("path", "docs"));
    }

    @Test
    void initialDeltaAndAuthoritativeDonePublishOnlyNewTextOnce() throws Exception {
        var initial = functionItem("call_1", "fc_1", "tool", "{\"a\":\"x", null);
        var terminal = functionItem("call_1", "fc_1", "tool", "{\"a\":\"xyz\"}", null);
        var events = collect(sse(
            created(),
            itemEvent("added", 0, initial),
            event("{\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,"
                + "\"delta\":\"y\"}"),
            event("{\"type\":\"response.function_call_arguments.done\",\"output_index\":0,"
                + "\"arguments\":\"{\\\"a\\\":\\\"xyz\\\"}\"}"),
            itemEvent("done", 0, terminal),
            completed(terminal, "resp_terminal")));

        var start = last(events, StreamEvent.ToolCallStart.class);
        assertThat(start.partial().responseId()).isEqualTo("resp_1");
        var startBlock = (ContentBlock.ToolUseContent) start.partial().content().get(0);
        assertThat(startBlock.arguments()).isEqualTo(Map.of("a", "x"));
        assertThat(events).filteredOn(StreamEvent.ToolCallDelta.class::isInstance)
            .map(StreamEvent.ToolCallDelta.class::cast)
            .extracting(StreamEvent.ToolCallDelta::jsonDelta)
            .containsExactly("y", "z\"}");
        assertThat(last(events, StreamEvent.ToolCallEnd.class).arguments())
            .isEqualTo(Map.of("a", "xyz"));
        assertThat(last(events, StreamEvent.StreamDone.class).partial().responseId())
            .isEqualTo("resp_terminal");
    }

    @Test
    void interleavedOutputIndexesKeepArgumentsIdentityAndNamespace() throws Exception {
        var a = functionItem("call_a", "fc_a", "tool-a", "", "ns-a");
        var b = functionItem("call_b", "fc_b", "tool-b", "", "ns-b");
        var aDone = functionItem("call_a", "fc_a", "tool-a", "{\"a\":1}", null);
        var bDone = functionItem("call_b", "fc_b", "tool-b", "{\"b\":2}", "ns-b-final");
        var events = collect(sse(
            created(), itemEvent("added", 0, a), itemEvent("added", 1, b),
            argumentDelta(0, "{\"a\":1}"), argumentDelta(1, "{\"b\":2}"),
            itemEvent("done", 1, bDone), itemEvent("done", 0, aDone),
            completed(aDone + "," + bDone)));

        assertThat(events).filteredOn(StreamEvent.ToolCallEnd.class::isInstance)
            .map(StreamEvent.ToolCallEnd.class::cast)
            .extracting(StreamEvent.ToolCallEnd::contentIndex)
            .containsExactly(1, 0);
        assertThat(last(events, StreamEvent.StreamDone.class).partial().content()).containsExactly(
            new ContentBlock.ToolUseContent("call_a|fc_a", "tool-a", Map.of("a", 1),
                null, "ns-a", null),
            new ContentBlock.ToolUseContent("call_b|fc_b", "tool-b", Map.of("b", 2),
                null, "ns-b-final", null));
    }

    private static String functionItem(
            String callId, String itemId, String name, String arguments, String namespace) {
        return "{\"id\":\"" + itemId + "\",\"type\":\"function_call\",\"call_id\":\""
            + callId + "\",\"name\":\"" + name + "\",\"status\":\"completed\","
            + "\"arguments\":\"" + arguments.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"" + (namespace == null ? "" : ",\"namespace\":\"" + namespace + "\"") + "}";
    }

    private static String itemEvent(String phase, int index, String item) {
        return event("{\"type\":\"response.output_item." + phase + "\",\"output_index\":"
            + index + ",\"item\":" + item + "}");
    }

    private static String argumentDelta(int index, String delta) {
        return event("{\"type\":\"response.function_call_arguments.delta\",\"output_index\":"
            + index + ",\"delta\":\"" + delta.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"}");
    }

    /** 收尾项带完整 arguments 的形状。 */
    private static String functionCallWithArguments() {
        return "{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\","
            + "\"name\":\"ls\",\"status\":\"completed\","
            + "\"arguments\":\"{\\\"path\\\":\\\"docs\\\"}\"}";
    }

    // ── 夹具脚手架（同 ResponsesReasoningCaptureTest）────────────────────

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
        return completed(outputItem, "resp_1");
    }

    private static String completed(String outputItem, String responseId) {
        return event("{\"type\":\"response.completed\",\"response\":{\"id\":\"" + responseId + "\","
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
