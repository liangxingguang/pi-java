package com.pijava.ai.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * B19 收侧：{@code openai-completions} 车道**读取**响应侧 reasoning
 * （docs/31 §8.35.1，pi {@code openai-completions.ts:597-620}）。
 *
 * <p>行车事故的根因：该车道只读 {@code delta.content()}，relay 送回的推理文本
 * 整段丢弃 ⇒ 「1014 output token 换回一条空消息、且 {@code stopReason=stop}」。
 * pi 依次试 {@code reasoning_content} / {@code reasoning} / {@code reasoning_text}，
 * 取**第一个非空字符串**，并**用命中的字段名当 {@code thinkingSignature}**
 * —— 重放时靠这个签名自描述该发回哪个字段（见同族的
 * {@link OpenAICompletionsReasoningReplayTest}）。</p>
 *
 * <p>夹具走**真实 SDK 路径**：本地 HTTP server 喂线格 SSE，与
 * {@code OpenAIResponsesApiTest} 同一形态（不是手搓 JSON 解析）。</p>
 */
class OpenAICompletionsReasoningTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reasoningContentDeltaEmitsThinkingBlockSignedWithFieldName() throws Exception {
        var api = api(startServer(sse(
            chunk("{\"reasoning_content\":\"让我想想\"}"),
            chunk("{\"content\":\"25\"}"),
            finalChunk("stop"))));

        var events = collect(api, "1–100 之间质数共几个？");

        assertThat(classes(events)).containsExactly("Start", "ThinkingStart",
            "ThinkingDelta", "TextStart", "TextDelta", "ThinkingEnd", "TextEnd",
            "StreamDone");
        assertThat(thinkingSignature(events)).isEqualTo("reasoning_content");
        assertThat(thinkingText(events)).isEqualTo("让我想想");
    }

    @Test
    void reasoningFieldIsAcceptedAndBecomesTheSignature() throws Exception {
        var api = api(startServer(sse(
            chunk("{\"reasoning\":\"a\"}"),
            chunk("{\"content\":\"answer\"}"),
            finalChunk("stop"))));

        var events = collect(api, "hi");

        assertThat(thinkingSignature(events)).isEqualTo("reasoning");
    }

    @Test
    void probeOrderPrefersReasoningContentWhenBothArePresent() throws Exception {
        // pi 的探测序是 ["reasoning_content","reasoning","reasoning_text"]，
        // chutes.ai 会同时返回前两个同值字段（openai-completions.ts:600-602 注释）。
        var api = api(startServer(sse(
            chunk("{\"reasoning\":\"from-reasoning\",\"reasoning_content\":\"from-content\"}"),
            chunk("{\"content\":\"x\"}"),
            finalChunk("stop"))));

        var events = collect(api, "hi");

        assertThat(thinkingSignature(events)).isEqualTo("reasoning_content");
        assertThat(thinkingText(events)).isEqualTo("from-content");
    }

    @Test
    void nonStringReasoningValueIsIgnored() throws Exception {
        // pi 只认 typeof === "string"（openai-completions.ts:608）。
        var api = api(startServer(sse(
            chunk("{\"reasoning_content\":123}"),
            chunk("{\"content\":\"x\"}"),
            finalChunk("stop"))));

        var events = collect(api, "hi");

        assertThat(classes(events)).containsExactly("Start", "TextStart",
            "TextDelta", "TextEnd", "StreamDone");
    }

    @Test
    void reasoningAccumulatesIntoASingleBlock() throws Exception {
        var api = api(startServer(sse(
            chunk("{\"reasoning_content\":\"A\"}"),
            chunk("{\"reasoning_content\":\"B\"}"),
            chunk("{\"content\":\"x\"}"),
            finalChunk("stop"))));

        var events = collect(api, "hi");

        assertThat(classes(events)).containsExactly("Start", "ThinkingStart",
            "ThinkingDelta", "ThinkingDelta", "TextStart", "TextDelta",
            "ThinkingEnd", "TextEnd", "StreamDone");
        assertThat(thinkingText(events)).isEqualTo("AB");
    }

    @Test
    void endsFollowBlockCreationOrderNotAFixedOrder() throws Exception {
        // 同一个 delta 里同时有 content 与 reasoning_content 时，pi 先处理 content
        // （:584 建块→推 delta）再处理 reasoning（:597 建块→推 delta）⇒ 文本块先建；
        // 收尾按**建块序**发（:674-676），故 text_end 在 thinking_end 之前。
        var api = api(startServer(sse(
            chunk("{\"content\":\"x\",\"reasoning_content\":\"R\"}"),
            finalChunk("stop"))));

        var events = collect(api, "hi");

        assertThat(classes(events)).containsExactly("Start", "TextStart",
            "TextDelta", "ThinkingStart", "ThinkingDelta", "TextEnd",
            "ThinkingEnd", "StreamDone");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private OpenAICompletionsApi api(String baseUrl) {
        return new OpenAICompletionsApi(
            new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(10), 0, Map.of()),
            "OPENAI_API_KEY");
    }

    private List<StreamEvent> collect(OpenAICompletionsApi api, String prompt) {
        var events = new ArrayList<StreamEvent>();
        var request = StreamRequest.of(ModelId.of("openai", "glm-5.3-flash"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent(prompt)))));
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    private String startServer(String sseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    private static List<String> classes(List<StreamEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList();
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance)
            .map(type::cast).reduce((a, b) -> b).orElseThrow();
    }

    /** 思考块的签名 —— 事件 `partial` 上的 thinking 块取。 */
    private static String thinkingSignature(List<StreamEvent> events) {
        var blocks = last(events, StreamEvent.ThinkingStart.class).partial().content();
        return blocks.stream()
            .filter(b -> b instanceof ContentBlock.ThinkingContent)
            .map(b -> ((ContentBlock.ThinkingContent) b).signature())
            .findFirst().orElse(null);
    }

    /** 终局消息里的思考文本。 */
    private static String thinkingText(List<StreamEvent> events) {
        return last(events, StreamEvent.StreamDone.class).partial().content().stream()
            .filter(b -> b instanceof ContentBlock.ThinkingContent)
            .map(b -> ((ContentBlock.ThinkingContent) b).text())
            .reduce("", String::concat);
    }

    // ── SSE fixtures ────────────────────────────────────────────────────

    private static String sse(String... chunks) {
        return String.join("", chunks) + "data: [DONE]\n\n";
    }

    /** 一个 delta 帧；{@code finish_reason} 恒为 null（终帧用 {@link #finalChunk}）。 */
    private static String chunk(String deltaJson) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
            + "\"delta\":" + deltaJson + ",\"finish_reason\":null}]}\n\n";
    }

    private static String finalChunk(String finishReason) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
            + "\"delta\":{},\"finish_reason\":\"" + finishReason + "\"}]}\n\n";
    }
}
