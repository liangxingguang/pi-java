package com.pijava.ai.protocol;

import java.io.OutputStream;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B80：数组形态 {@code delta.content} 三支 —— 对应 pi
 * {@code test/mistral-http-transport.test.ts}：
 * <ul>
 *   <li>:238-323 "parses native thinking, text, fragmented tool calls, and cached-token usage"；</li>
 *   <li>:325-365 "#9674: GLM empty content deltas … must not open blocks or split thinking"；</li>
 *   <li>串形态回归 ＋ 未知 type 静默忽略（pi 无 else 支，:706）。</li>
 * </ul>
 *
 * <p>修复前 {@code (String) delta.get("content")} 对数组直接 ClassCastException ⇒ 整流一条
 * error。走真实 HTTP/SSE 路径，断言事件序与终态消息块表。</p>
 */
class MistralStreamContentWireTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── T1：pi http-transport.test.ts:238-323 ────────────────────────────

    @Test
    void parsesThinkingTextAndFragmentedToolCalls() throws Exception {
        var events = collect(
            data("\"id\":\"response-1\",\"choices\":[{\"index\":0,\"finish_reason\":null,"
                + "\"delta\":{\"content\":[{\"type\":\"thinking\","
                + "\"thinking\":[{\"type\":\"text\",\"text\":\"reason\"}]}]}}]"),
            data("\"id\":\"response-1\",\"choices\":[{\"index\":0,\"finish_reason\":null,"
                + "\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"answer\"}]}}]"),
            data("\"id\":\"response-1\",\"choices\":[{\"index\":0,\"finish_reason\":null,"
                + "\"delta\":{\"tool_calls\":[{\"id\":\"abc123456\",\"index\":0,"
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"query\\\":\"}}]}}]"),
            data("\"id\":\"response-1\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,"
                + "\"total_tokens\":14,\"prompt_tokens_details\":{\"cached_tokens\":3}},"
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\","
                + "\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"name\":\"\",\"arguments\":\"\\\"pi\\\"}\"}}]}}]"));

        // 事件序：thinking 段 → text 段（换型先 end 再 start）→ 工具调用到达关 text 块。
        assertThat(kinds(events)).containsSubsequence(
            "ThinkingStart", "ThinkingDelta", "ThinkingEnd",
            "TextStart", "TextDelta", "TextEnd",
            "ToolCallStart", "ToolCallDelta", "ToolCallEnd", "StreamDone");

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(done.partial().rawStopReason()).isEqualTo("tool_calls");
        assertThat(done.partial().content()).hasSize(3);
        assertThat(done.partial().content().get(0))
            .isEqualTo(new ContentBlock.ThinkingContent("reason"));
        assertThat(done.partial().content().get(1))
            .isEqualTo(new ContentBlock.TextContent("answer"));
        var toolCall = (ContentBlock.ToolUseContent) done.partial().content().get(2);
        assertThat(toolCall.id()).isEqualTo("abc123456");
        assertThat(toolCall.name()).isEqualTo("lookup");
        assertThat(toolCall.arguments()).isEqualTo(Map.of("query", "pi"));
    }

    // ── T2：pi :325-365（GLM 九 delta）───────────────────────────────────

    @Test
    void emptyContentDeltasDoNotOpenOrSplitBlocks() throws Exception {
        String thinking = "{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"%s\"}]}";
        var deltas = List.of(
            "{\"content\":\"\"}",
            "{\"content\":[" + thinking.formatted("first part,") + "]}",
            "{\"content\":\"\"}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"\"}]}",
            "{\"content\":[" + thinking.formatted(" second part.")
                + ",{\"type\":\"text\",\"text\":\"Reading.\"}]}",
            "{\"content\":\"\",\"tool_calls\":[{\"index\":0,\"id\":\"abc123456\","
                + "\"function\":{\"name\":\"read\",\"arguments\":\"\"}}]}",
            "{\"content\":\"\",\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"name\":\"\",\"arguments\":\"{\\\"path\\\":\"}}]}",
            "{\"content\":\"\",\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"name\":\"\",\"arguments\":\"\\\"a.txt\\\"}\"}}]}",
            "{\"content\":\"\"}");

        var lines = new java.util.ArrayList<String>();
        for (int i = 0; i < deltas.size(); i++) {
            String finish = i == deltas.size() - 1 ? ",\"finish_reason\":\"tool_calls\""
                                                   : ",\"finish_reason\":null";
            lines.add(data("\"id\":\"response-1\",\"choices\":[{\"index\":0" + finish
                + ",\"delta\":" + deltas.get(i) + "}]"));
        }
        var events = collect(lines.toArray(String[]::new));

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(done.partial().content()).hasSize(3);
        assertThat(done.partial().content().get(0))
            .isEqualTo(new ContentBlock.ThinkingContent("first part, second part."));
        assertThat(done.partial().content().get(1))
            .isEqualTo(new ContentBlock.TextContent("Reading."));
        var toolCall = (ContentBlock.ToolUseContent) done.partial().content().get(2);
        assertThat(toolCall.id()).isEqualTo("abc123456");
        assertThat(toolCall.name()).isEqualTo("read");
        assertThat(toolCall.arguments()).isEqualTo(Map.of("path", "a.txt"));

        // 恰一个 thinking 块与一个 text 块：空 delta 没开过块、thinking 没被切碎。
        assertThat(events).filteredOn(StreamEvent.ThinkingStart.class::isInstance).hasSize(1);
        assertThat(events).filteredOn(StreamEvent.TextStart.class::isInstance).hasSize(1);
    }

    // ── T3：串形态回归 ＋ 未知 type 忽略 ──────────────────────────────────

    @Test
    void stringContentStillOpensOneTextBlock() throws Exception {
        var events = collect(text("hello"), finish("stop"));

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo(StopReason.STOP);
        assertThat(done.partial().content())
            .containsExactly(new ContentBlock.TextContent("hello"));
        assertThat(events).filteredOn(StreamEvent.TextStart.class::isInstance).hasSize(1);
    }

    /** pi 无 else 支（:706）：数组里不认识的 type 静默忽略；认识的 text 照常折进来。 */
    @Test
    void unknownArrayItemTypesAreIgnored() throws Exception {
        var events = collect(
            data("\"choices\":[{\"index\":0,\"finish_reason\":null,\"delta\":{\"content\":["
                + "{\"type\":\"image_url\",\"image_url\":\"https://example.test/x.png\"},"
                + "{\"type\":\"text\",\"text\":\"ok\"},"
                + "{\"type\":\"something_new\",\"foo\":1}]}}]"),
            finish("stop"));

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.partial().content())
            .containsExactly(new ContentBlock.TextContent("ok"));
        assertThat(events).filteredOn(StreamEvent.ThinkingStart.class::isInstance).isEmpty();
    }

    // ── 夹具脚手架（与 MistralConversationsApiTest 同形） ─────────────────

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
        var api = new MistralConversationsApi(new ApiOptions(
            "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key",
            Duration.ofSeconds(5), 0, Map.of()));
        var request = StreamRequest.of(ModelId.of("mistral", "mistral-large-latest"),
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

    private static List<String> kinds(List<StreamEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()
            .replaceFirst("^.*\\$", "")).toList();
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast)
            .reduce((a, b) -> b).orElseThrow();
    }

    private static String data(String json) {
        return "data: {" + json + "}\n\n";
    }

    private static String text(String value) {
        return data("\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + value + "\"}}]");
    }

    private static String finish(String reason) {
        return data("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
            + reason + "\"}]");
    }
}
