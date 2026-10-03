package com.pijava.ai.protocol;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.GrammarSampling;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.CatalogCompatRules;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/69：openai-responses 车道的 grammar custom tool 出站声明、历史回放
 * （custom_tool_call/custom_tool_call_output）与入站重组
 * （pi {@code openai-responses-shared.ts:306-316/359-378/504-527/670-680/726-740}）。
 */
class ResponsesGrammarToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── 出站：custom tool ───────────────────────────────────────────

    @Test
    void emitsCustomToolForLarkWhenGateOpen() throws Exception {
        var body = runRequest(gateOpenModel(), grammarTool("pick", lark()), null);

        var tool = JSON.readTree(body).path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("custom");
        assertThat(tool.path("name").asText()).isEqualTo("pick");
        assertThat(tool.path("description").asText()).isEqualTo("Pick one");
        var format = tool.path("format");
        assertThat(format.path("type").asText()).isEqualTo("grammar");
        assertThat(format.path("syntax").asText()).isEqualTo("lark");
        assertThat(format.path("definition").asText()).isEqualTo("root ::= \"x\"");
        // responses custom 是扁平 format（无 format.grammar 内层）。
        assertThat(format.has("grammar")).isFalse();
    }

    @Test
    void emitsRegexWhenOnlyRegexVariant() throws Exception {
        var body = runRequest(gateOpenModel(),
            grammarTool("pick", variant("openai_regex", "x+")), null);

        var format = JSON.readTree(body).path("tools").get(0).path("format");
        assertThat(format.path("syntax").asText()).isEqualTo("regex");
        assertThat(format.path("definition").asText()).isEqualTo("x+");
    }

    @Test
    void fallsBackToFunctionWhenGateClosed() throws Exception {
        var body = runRequest(gateClosedModel(), grammarTool("pick", lark()), null);

        var tool = JSON.readTree(body).path("tools").get(0);
        assertThat(tool.path("type").asText())
            .as("门关 ⇒ 静默回落 function（responses function 也是扁平结构）")
            .isEqualTo("function");
        assertThat(tool.path("name").asText()).isEqualTo("pick");
    }

    // ── 历史回放 ────────────────────────────────────────────────────

    @Test
    void replaysCustomToolCallAndOutputWithSanitizedInput() throws Exception {
        var body = runRequest(gateOpenModel(), grammarTool("pick", lark()),
            replayTranscript());

        JsonNode customCall = null;
        JsonNode customOutput = null;
        for (var item : JSON.readTree(body).path("input")) {
            if ("custom_tool_call".equals(item.path("type").asText())) {
                customCall = item;
            } else if ("custom_tool_call_output".equals(item.path("type").asText())) {
                customOutput = item;
            }
        }
        assertThat(customCall).as("assistant 块落 custom_tool_call").isNotNull();
        assertThat(customCall.path("call_id").asText()).isEqualTo("call-1");
        assertThat(customCall.path("name").asText()).isEqualTo("pick");
        assertThat(customCall.path("input").asText())
            .as("回放输入必须走 SanitizeUnicode（孤对被删）").isEqualTo("abcd");

        assertThat(customOutput).as("tool result 块落 custom_tool_call_output").isNotNull();
        assertThat(customOutput.path("call_id").asText()).isEqualTo("call-1");
        assertThat(customOutput.path("output").asText()).isEqualTo("ok");
    }

    // ── 入站重组 ────────────────────────────────────────────────────

    @Test
    void reassemblesCustomToolInputFromStreamEvents() throws Exception {
        var sse = sse(
            created(),
            itemAdded("""
                {"id":"ctc_1","type":"custom_tool_call","call_id":"call-1",
                 "name":"pick","input":""}"""),
            inputDelta("he"), inputDelta("llo"),
            inputDone("hello"),
            itemDone("""
                {"id":"ctc_1","type":"custom_tool_call","call_id":"call-1",
                 "name":"pick","input":"hello"}"""),
            completed());
        var events = runStream(gateOpenModel(), grammarTool("pick", lark()), sse);

        var start = last(events, StreamEvent.ToolCallStart.class);
        var startBlock = (ContentBlock.ToolUseContent)
            start.partial().content().get(start.contentIndex());
        assertThat(startBlock.id()).isEqualTo("call-1|ctc_1");
        assertThat(startBlock.name()).isEqualTo("pick");
        var end = last(events, StreamEvent.ToolCallEnd.class);
        assertThat(end.arguments()).containsEntry("query", "hello");
        assertThat(last(events, StreamEvent.StreamDone.class).reason())
            .isEqualTo("toolUse");
    }

    @Test
    void aRepeatedInputDoneProducesNoExtraDelta() throws Exception {
        var sse = sse(
            created(),
            itemAdded("""
                {"id":"ctc_1","type":"custom_tool_call","call_id":"call-1",
                 "name":"pick","input":"hello"}"""),
            inputDone("hello"), inputDone("hello"),
            itemDone("""
                {"id":"ctc_1","type":"custom_tool_call","call_id":"call-1",
                 "name":"pick","input":"hello"}"""),
            completed());
        var events = runStream(gateOpenModel(), grammarTool("pick", lark()), sse);

        // itemAdded 已经把初始 input "hello" 喂给缓冲（起点后的首片段）；两次 done
        // 幂等不发片段 ⇒ 恰一条 delta（来自 itemAdded）。
        var deltaCount = events.stream()
            .filter(StreamEvent.ToolCallDelta.class::isInstance).count();
        assertThat(deltaCount).isEqualTo(1L);
    }

    // ── 夹具：模型/工具/转录 ───────────────────────────────────────

    private static Map<String, String> lark() {
        return variant("openai_lark", "root ::= \"x\"");
    }

    private static Map<String, String> variant(String key, String value) {
        var map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }

    private static ToolDefinition grammarTool(String name, Map<String, String> variants) {
        var props = new LinkedHashMap<String, Object>();
        props.put("query", Map.of("type", "string"));
        var schema = Map.of("type", "object", "properties", props,
            "required", List.of("query"));
        return new ToolDefinition(name, "Pick one", schema, name, null, List.of(),
            "default", new GrammarSampling(variants));
    }

    private static ModelInfo gateOpenModel() {
        return new ModelInfo(ModelId.of("openai", "gpt-5"), "GPT-5",
            java.util.Set.of(com.pijava.ai.model.ModelCapability.TEXT),
            128_000, 16_384, false,
            com.pijava.ai.model.PricingInfo.UNKNOWN,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            Map.of(), Map.of(),
            CatalogCompatRules.openaiResponses("openai", "gpt-5"));
    }

    private static ModelInfo gateClosedModel() {
        return ModelInfo.minimal(ModelId.of("openai", "gpt-4o"));
    }

    private List<Message> replayTranscript() {
        var system = new Message.SystemMessage("base", Instant.EPOCH, Map.of(),
            List.of(grammarTool("pick", lark())), List.of());
        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent("call-1|ctc_1", "pick",
                Map.of("query", "ab" + (char) 0xD800 + "cd"))),
            "toolUse", null);
        var toolResult = new Message.ToolResultMessage("call-1|ctc_1", "pick",
            List.of(new ContentBlock.TextContent("ok")), false);
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("again")));
        return List.of(system, assistant, toolResult, user);
    }

    // ── 夹具：请求驱动 ─────────────────────────────────────────────

    /** Drive one request with a scripted empty 200; return the recorded request body. */
    private String runRequest(ModelInfo model, ToolDefinition requestTool,
                               List<Message> transcript) throws Exception {
        var sse = sse(created(), completedEmpty());
        var baseUrl = startServer(sse);
        var api = new OpenAIResponsesApi(options(baseUrl), "OPENAI_API_KEY");
        List<Message> actualTranscript;
        if (transcript != null) {
            actualTranscript = transcript;
        } else {
            actualTranscript = List.of(
                new Message.SystemMessage("base", Instant.EPOCH, Map.of(),
                    List.of(requestTool), List.of()),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));
        }
        var request = new StreamRequest(model, null, actualTranscript,
            List.of(), -1, -1, Map.of());
        collectRaw(api, request);
        return recordedBody;
    }

    /** Drive one stream with scripted SSE; return the collected events. */
    private List<StreamEvent> runStream(ModelInfo model, ToolDefinition requestTool,
                                         String sse) throws Exception {
        var baseUrl = startServer(sse);
        var api = new OpenAIResponsesApi(options(baseUrl), "OPENAI_API_KEY");
        var transcript = List.<Message>of(
            new Message.SystemMessage("base", Instant.EPOCH, Map.of(),
                List.of(requestTool), List.of()),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));
        var request = new StreamRequest(model, null, transcript,
            List.of(), -1, -1, Map.of());
        return collectRaw(api, request);
    }

    private String recordedBody = "";

    private List<StreamEvent> collectRaw(OpenAIResponsesApi api, StreamRequest request)
            throws InterruptedException {
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

    private static ApiOptions options(String baseUrl) {
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0, Map.of());
    }

    private String startServer(String sseBody) throws java.io.IOException {
        var http = HttpServer.create(new InetSocketAddress(0), 0);
        http.createContext("/v1/responses", exchange -> {
            var capture = new java.io.ByteArrayOutputStream();
            try (var in = exchange.getRequestBody()) {
                in.transferTo(capture);
            }
            recordedBody = capture.toString(StandardCharsets.UTF_8);
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        http.start();
        server = http;
        recordedBody = "";
        return "http://localhost:" + http.getAddress().getPort() + "/v1";
    }

    // ── 夹具：SSE ──────────────────────────────────────────────────

    private static String sse(String... events) {
        return String.join("", events) + "data: [DONE]\n\n";
    }

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String inputDelta(String delta) throws Exception {
        var node = JSON.createObjectNode()
            .put("type", "response.custom_tool_call_input.delta")
            .put("item_id", "ctc_1")
            .put("output_index", 0)
            .put("delta", delta);
        return event(JSON.writeValueAsString(node));
    }

    private static String inputDone(String input) throws Exception {
        var node = JSON.createObjectNode()
            .put("type", "response.custom_tool_call_input.done")
            .put("item_id", "ctc_1")
            .put("output_index", 0)
            .put("input", input);
        return event(JSON.writeValueAsString(node));
    }

    private static String itemAdded(String itemJson) throws Exception {
        var item = JSON.readTree(itemJson);
        var wrapper = JSON.createObjectNode()
            .put("type", "response.output_item.added")
            .put("output_index", 0);
        wrapper.set("item", item);
        return event(JSON.writeValueAsString(wrapper));
    }

    private static String itemDone(String itemJson) throws Exception {
        var item = JSON.readTree(itemJson);
        var wrapper = JSON.createObjectNode()
            .put("type", "response.output_item.done")
            .put("output_index", 0);
        wrapper.set("item", item);
        return event(JSON.writeValueAsString(wrapper));
    }

    private static String created() throws Exception {
        var response = JSON.createObjectNode()
            .put("id", "resp_1")
            .put("status", "in_progress")
            .put("model", "gpt-5")
            .put("parallel_tool_calls", true);
        response.set("output", JSON.createArrayNode());
        response.set("tools", JSON.createArrayNode());
        response.putNull("usage");
        var wrapper = JSON.createObjectNode()
            .put("type", "response.created");
        wrapper.set("response", response);
        return event(JSON.writeValueAsString(wrapper));
    }

    private static String completed() throws Exception {
        var outputItem = JSON.readTree("""
            {"id":"ctc_1","type":"custom_tool_call","call_id":"call-1",
             "name":"pick","input":"hello"}""");
        var response = JSON.createObjectNode()
            .put("id", "resp_1")
            .put("status", "completed")
            .put("model", "gpt-5")
            .put("parallel_tool_calls", true);
        response.set("output", JSON.createArrayNode().add(outputItem));
        response.set("tools", JSON.createArrayNode());
        response.set("usage", JSON.readTree("""
            {"input_tokens":10,"output_tokens":5,"total_tokens":15,
             "input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},
             "output_tokens_details":{"reasoning_tokens":0}}"""));
        var wrapper = JSON.createObjectNode()
            .put("type", "response.completed");
        wrapper.set("response", response);
        return event(JSON.writeValueAsString(wrapper));
    }

    private static String completedEmpty() throws Exception {
        var response = JSON.createObjectNode()
            .put("id", "resp_1")
            .put("status", "completed")
            .put("model", "gpt-5")
            .put("parallel_tool_calls", true);
        response.set("output", JSON.createArrayNode());
        response.set("tools", JSON.createArrayNode());
        response.putNull("usage");
        var wrapper = JSON.createObjectNode()
            .put("type", "response.completed");
        wrapper.set("response", response);
        return event(JSON.writeValueAsString(wrapper));
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast)
            .reduce((a, b) -> b).orElseThrow(
                () -> new AssertionError("no " + type.getSimpleName()));
    }
}
