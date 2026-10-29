package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

/**
 * 批 30（ai#8）：Responses 车道 {@code namespace} 全链（pi
 * {@code openai-responses-shared.ts:316/:325} 输入侧同模型闸门、
 * {@code :492/:513} 起点建块、{@code :717/:733} 收尾回填）。
 */
class ResponsesNamespaceWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NS = "mcp__docs";

    private HttpServer server;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── 输入侧（回放）：同模型闸门 ─────────────────────────────────────

    private static Message.AssistantMessage assistant(String model, String namespace) {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent("call_1|fc_1000", "get_weather",
                Map.of(), null, namespace)),
            StopReason.TOOL_USE, null, "openai-responses", "openai", model, zeroUsage(),
            Instant.EPOCH, null, null);
    }

    private static Usage zeroUsage() {
        return new Usage(0, 0, 0, 0, null, null, 0, Usage.Cost.zero());
    }

    /** 驱动车道，返回真出站请求体（桩回 400 收场，请求体已录）。 */
    private static JsonNode capture(String targetModel, List<Message> history) throws Exception {
        try (var stub = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(
                new ApiOptions(stub.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "OPENAI_API_KEY");
            var modelInfo = ModelInfo.minimal(ModelId.of("openai", targetModel));
            var request = new StreamRequest(modelInfo, null, history, List.of(), -1, -1);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录，流怎么结束无关。
            }
            assertThat(stub.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(stub.body());
        }
    }

    private static JsonNode itemOfType(JsonNode body, String type) {
        for (var item : body.path("input")) {
            if (type.equals(item.path("type").asText())) {
                return item;
            }
        }
        throw new AssertionError("missing item of type " + type);
    }

    @Test
    void replayCarriesNamespaceUnderSameModel() throws Exception {
        var body = capture("gpt-5.4", List.of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("weather?"))),
            assistant("gpt-5.4", NS)));
        assertThat(itemOfType(body, "function_call").path("namespace").asText()).isEqualTo(NS);
    }

    @Test
    void replayDropsNamespaceAcrossModels() throws Exception {
        var body = capture("gpt-5.5", List.of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("weather?"))),
            assistant("gpt-5.4", NS)));
        assertThat(itemOfType(body, "function_call").has("namespace")).isFalse();
    }

    @Test
    void replayWithoutNamespaceEmitsNoKey() throws Exception {
        var body = capture("gpt-5.4", List.of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("weather?"))),
            assistant("gpt-5.4", null)));
        assertThat(itemOfType(body, "function_call").has("namespace")).isFalse();
    }

    // ── 输出侧（流式）：起点建块＋收尾回填 ─────────────────────────────

    private static String functionCallItem(String callId, String itemId, String args,
                                           String namespace) {
        return "{\"id\":\"" + itemId + "\",\"type\":\"function_call\",\"status\":\"completed\","
            + "\"call_id\":\"" + callId + "\",\"name\":\"get_weather\",\"arguments\":\"" + args
            + "\"" + (namespace == null ? "" : ",\"namespace\":\"" + namespace + "\"") + "}";
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
        var done = new CountDownLatch(1);
        api.stream(request, ApiOptions.defaults()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(StreamEvent e) { events.add(e); }
            @Override public void onError(Throwable t) { done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).as("stream completes").isTrue();
        return events;
    }

    private static String sse(String... events) {
        return String.join("", events);
    }

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String created() {
        return event("{\"type\":\"response.created\",\"response\":{\"id\":\"r1\"}}");
    }

    private static String completedWithOutput(String outputItem) {
        return event("{\"type\":\"response.completed\",\"response\":{\"id\":\"r1\","
            + "\"status\":\"completed\",\"output\":[" + outputItem + "],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}");
    }

    private static ContentBlock.ToolUseContent toolBlockOf(List<StreamEvent> events) {
        var lastDone = events.stream().filter(e -> e instanceof StreamEvent.StreamDone)
            .reduce((a, b) -> b).orElseThrow();
        return (ContentBlock.ToolUseContent)
            ((StreamEvent.StreamDone) lastDone).partial().content().get(0);
    }

    @Test
    void namespaceFromAddedItemLandsOnTheBlock() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                + functionCallItem("call_1", "fc_1", "", NS) + "}"),
            event("{\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,"
                + "\"delta\":\"{}\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
                + functionCallItem("call_1", "fc_1", "{}", null) + "}"),
            completedWithOutput(functionCallItem("call_1", "fc_1", "{}", null))));

        assertThat(toolBlockOf(events).namespace()).isEqualTo(NS);
    }

    @Test
    void namespaceFromDoneItemOverridesStartValue() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                + functionCallItem("call_1", "fc_1", "", "mcp__old") + "}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
                + functionCallItem("call_1", "fc_1", "{}", NS) + "}"),
            completedWithOutput(functionCallItem("call_1", "fc_1", "{}", NS))));

        assertThat(toolBlockOf(events).namespace()).isEqualTo(NS);
        // 帧路径（pi frame toolcall_end 的 namespace 字段对位）。
        var end = (StreamEvent.ToolCallEnd) events.stream()
            .filter(e -> e instanceof StreamEvent.ToolCallEnd).findFirst().orElseThrow();
        assertThat(end.namespace()).isEqualTo(NS);
    }

    @Test
    void absentNamespaceStaysNull() throws Exception {
        var events = collect(sse(
            created(),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                + functionCallItem("call_1", "fc_1", "", null) + "}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
                + functionCallItem("call_1", "fc_1", "{}", null) + "}"),
            completedWithOutput(functionCallItem("call_1", "fc_1", "{}", null))));

        assertThat(toolBlockOf(events).namespace()).isNull();
    }
}
