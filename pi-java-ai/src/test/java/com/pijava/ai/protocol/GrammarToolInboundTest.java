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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/69：openai-completions 车道入站 custom tool_call 流式重组
 * （pi {@code openai-completions.ts:494-551/646-654}）与
 * {@link GrammarInputBuffer} 状态机单测。
 */
class GrammarToolInboundTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── 端到端：custom chunks → tool call 重组 ──────────────────────

    @Test
    void reassemblesCustomToolInputAcrossChunks() throws Exception {
        var events = collect(List.of(
            customChunk("call-1", "pick", "he"),
            customChunk(null, null, "llo"),
            finishChunk("tool_calls")));

        var start = last(events, StreamEvent.ToolCallStart.class);
        var startBlock = (ContentBlock.ToolUseContent)
            start.partial().content().get(start.contentIndex());
        assertThat(startBlock.id()).isEqualTo("call-1");
        assertThat(startBlock.name()).isEqualTo("pick");
        var end = last(events, StreamEvent.ToolCallEnd.class);
        assertThat(end.id()).isEqualTo("call-1");
        assertThat(end.name()).isEqualTo("pick");
        assertThat(end.arguments()).containsEntry("query", "hello");

        // toolcall_delta 片段拼回是合法 JSON（pi 形状：{"query":"..."} 前缀流）。
        var assembled = new StringBuilder();
        events.stream().filter(StreamEvent.ToolCallDelta.class::isInstance)
            .forEach(d -> assembled.append(((StreamEvent.ToolCallDelta) d).jsonDelta()));
        var parsed = JSON.readValue(assembled.toString(), Object.class);
        assertThat(parsed).isEqualTo(Map.of("query", "hello"));
    }

    @Test
    void firstFrameWithOnlyIdFallsBackToTheInputProperty() throws Exception {
        var events = collect(List.of(
            customChunk("call-1", null, null),
            customChunk(null, "pick", "hello"),
            finishChunk("tool_calls")));

        var end = last(events, StreamEvent.ToolCallEnd.class);
        // pi ensureToolCallBlock：首帧无 name ⇒ property 回落 "input"（注释：不应被走到）。
        assertThat(end.arguments()).containsEntry("input", "hello");
    }

    @Test
    void anUnknownCustomToolNameAlsoFallsBackToInput() throws Exception {
        var events = collect(List.of(
            customChunk("call-2", "madeup", "hi"),
            finishChunk("tool_calls")));

        var end = last(events, StreamEvent.ToolCallEnd.class);
        assertThat(end.arguments()).containsEntry("input", "hi");
    }

    @Test
    void aNonMonotonicNextInputIsAnError() {
        // 防御性守卫（pi constrained-sampling.ts:167-169）：正常逐帧 append 结构上
        // 单调；仅当 server 帧不是增量（直接给出不以前缀开头的串）才命中。
        var buffer = new GrammarInputBuffer("query");
        buffer.append("hello", false);
        assertThatThrownBy(() -> buffer.append("heX", false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(
                "grammar tool input for property \"query\" changed non-monotonically");
    }

    // ── GrammarInputBuffer 状态机单测 ───────────────────────────────

    @Test
    void bufferProducesJsonFragmentsAndCloses() {
        var buffer = new GrammarInputBuffer("query");
        assertThat(buffer.append("he", false)).isEqualTo("{\"query\":\"he");
        assertThat(buffer.append("hello", false)).isEqualTo("llo");
        assertThat(buffer.append("hello", true)).isEqualTo("\"}");
    }

    @Test
    void bufferEscapesSpecialCharacters() {
        var buffer = new GrammarInputBuffer("q");
        assertThat(buffer.append("a\"b\\\nc", true))
            .isEqualTo("{\"q\":\"a\\\"b\\\\\\nc\"}");
    }

    @Test
    void aRepeatedCloseWithTheSameInputIsIdempotent() {
        var buffer = new GrammarInputBuffer("query");
        buffer.append("x", true);
        assertThat(buffer.append("x", true)).isNull();
    }

    @Test
    void changingTheInputAfterCloseIsAnError() {
        var buffer = new GrammarInputBuffer("query");
        buffer.append("x", true);
        assertThatThrownBy(() -> buffer.append("y", false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("grammar tool input for property \"query\" changed after it was closed");
    }

    // ── 夹具脚手架 ───────────────────────────────────────────────────

    private static final ToolDefinition GRAMMAR_TOOL = new ToolDefinition(
        "pick", "Pick one", grammarSchema(), "pick", null, List.of(), "default",
        new GrammarSampling(Map.of("openai_lark", "root ::= .*")));

    private static Map<String, Object> grammarSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("query", Map.of("type", "string"));
        return Map.of("type", "object", "properties", props,
            "required", List.of("query"));
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

    /** One custom-tool-call chunk; each field is omitted when its argument is null. */
    private static String customChunk(String id, String name, String input) throws Exception {
        var custom = JSON.createObjectNode();
        if (name != null) {
            custom.put("name", name);
        }
        if (input != null) {
            custom.put("input", input);
        }
        var toolCall = JSON.createObjectNode();
        toolCall.put("index", 0);
        if (id != null) {
            toolCall.put("id", id);
        }
        toolCall.put("type", "custom");
        toolCall.set("custom", custom);
        var delta = JSON.createObjectNode();
        delta.set("tool_calls", JSON.createArrayNode().add(toolCall));
        return chunkFrame(delta, null);
    }

    private static String finishChunk(String finishReason) throws Exception {
        return chunkFrame(JSON.createObjectNode(), finishReason);
    }

    private static String chunkFrame(
            com.fasterxml.jackson.databind.node.ObjectNode delta,
            String finishReason) throws Exception {
        var choice = JSON.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        var root = JSON.createObjectNode();
        root.put("id", "c1");
        root.put("object", "chat.completion.chunk");
        root.put("created", 1);
        root.put("model", "gpt-5");
        root.set("choices", JSON.createArrayNode().add(choice));
        return "data: " + JSON.writeValueAsString(root) + "\n\n";
    }

    private List<StreamEvent> collect(List<String> chunks) throws Exception {
        var sse = String.join("", chunks) + "data: [DONE]\n\n";
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
            "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key",
            Duration.ofSeconds(5), 0, Map.of()), "OPENAI_API_KEY");

        var transcript = List.<Message>of(
            new Message.SystemMessage("base", Instant.EPOCH, Map.of(),
                List.of(GRAMMAR_TOOL), List.of()),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));
        var request = new StreamRequest(gateOpenModel(), null, transcript,
            List.of(), -1, -1, Map.of());

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

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast)
            .reduce((a, b) -> b).orElseThrow(
                () -> new AssertionError("no " + type.getSimpleName()));
    }
}
