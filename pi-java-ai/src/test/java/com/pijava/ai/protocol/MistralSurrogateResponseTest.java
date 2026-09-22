package com.pijava.ai.protocol;

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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A0 步5（{@code docs/43 D4}）：Mistral 车道的**响应路径**净化 —— pi 的三个响应侧
 * 调用点里，pi-java 只有<b>一个</b>面存在。
 *
 * <table>
 *   <caption>pi → java 落点对照</caption>
 *   <tr><th>pi</th><th>语义</th><th>java</th></tr>
 *   <tr><td>{@code :626}</td><td>{@code delta.content} 的**字符串**项</td><td>{@code processSseData} 的 content 分支</td></tr>
 *   <tr><td>{@code :648}</td><td>{@code delta.content[].type == "thinking"} 的增量</td><td>—（本车道不解析数组形态 content）</td></tr>
 *   <tr><td>{@code :667}</td><td>{@code delta.content[].type == "text"} 的项</td><td>同上（无面）</td></tr>
 * </table>
 *
 * <p>⚠️ 另两个面**不是「漏了净化」而是整个形态没解析**（{@code delta.content} 只按
 * {@code String} 读）—— 属本车道的既有形态缺口，登记在 {@code docs/43 §6}，不在本包范围。</p>
 *
 * <p>夹具形态与 {@code MistralConversationsApiTest} 同（本地 server 喂 SSE）：让 provider
 * 回一条含孤对代理的 delta，断言**事件里的文本已被净化**。JSON 里的 {@code \uD83D} 转义
 * 会被解析器还原成裸孤高代理 —— 正是 pi 那条缝要处理的输入形态。</p>
 */
class MistralSurrogateResponseTest {

    private static final String EMOJI = "🙈";
    /** 线格形态：JSON 转义写在 SSE 字节里（解析后是裸孤高代理）。 */
    private static final String DIRTY_JSON = "Text \\uD83D here";
    private static final String CLEAN = "Text  here";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void stringContentDeltaIsSanitized() throws Exception {
        var events = collect(data("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
            + DIRTY_JSON + "\"}}]}"));

        assertThat(deltas(events, StreamEvent.TextDelta.class))
            .as("流式增量已被净化").containsExactly(CLEAN);
    }

    @Test
    void pairedEmojiDeltaSurvives() throws Exception {
        var events = collect(data("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi \\uD83D\\uDE48 there\"}}]}"));

        assertThat(deltas(events, StreamEvent.TextDelta.class))
            .as("配对 emoji 逐字保留").containsExactly("Hi " + EMOJI + " there");
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    private static <T extends StreamEvent> List<String> deltas(
            List<StreamEvent> events, Class<T> type) {
        return events.stream()
            .filter(type::isInstance)
            .map(e -> e instanceof StreamEvent.TextDelta t ? t.delta()
                : ((StreamEvent.ThinkingDelta) e).delta())
            .toList();
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

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
}