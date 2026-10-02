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
 * <b>Batch F 步 2</b>（{@code docs/67}）：Google thoughtSignature 的**采集** ——
 * 签名可附着在任意 part 上（text／thought:true 的 thinking／functionCall），
 * pi 逐字落盘（{@code JSON.stringify}），故终态消息块上必须带得出。
 *
 * <p>逐字对照 pi（{@code google-generative-ai.ts:145-168} 文本/思考 retain、
 * :201-207 functionCall 展开）。签名 retain 语义见
 * {@code google-shared.ts:139-143}：后到的非空值覆盖、空值（键缺席）不擦除。</p>
 */
class GoogleThoughtSignatureCaptureTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void capturesSignatureOnTextPart() throws Exception {
        var events = collect(
            data("\"content\":{\"parts\":[{\"text\":\"answer\",\"thoughtSignature\":\"U0lHTg==\"}],"
                + "\"role\":\"model\"}"),
            finish("STOP"));

        var done = last(events, StreamEvent.StreamDone.class);
        var block = (ContentBlock.TextContent) done.partial().content().get(0);
        assertThat(block.text()).isEqualTo("answer");
        assertThat(block.textSignature()).isEqualTo("U0lHTg==");
    }

    @Test
    void capturesSignatureOnThinkingPart() throws Exception {
        var events = collect(
            data("\"content\":{\"parts\":[{\"text\":\"hmm\",\"thought\":true,"
                + "\"thoughtSignature\":\"U0lHTg==\"}],\"role\":\"model\"}"),
            finish("STOP"));

        var done = last(events, StreamEvent.StreamDone.class);
        var block = (ContentBlock.ThinkingContent) done.partial().content().get(0);
        assertThat(block.text()).isEqualTo("hmm");
        assertThat(block.signature()).isEqualTo("U0lHTg==");
    }

    @Test
    void capturesSignatureOnFunctionCallPart() throws Exception {
        var events = collect(data(
            "\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"write\",\"args\":{}},"
            + "\"thoughtSignature\":\"U0lHTg==\"}],\"role\":\"model\"},"
            + "\"finishReason\":\"STOP\""));

        var done = last(events, StreamEvent.StreamDone.class);
        var block = (ContentBlock.ToolUseContent) done.partial().content().get(0);
        assertThat(block.name()).isEqualTo("write");
        assertThat(block.thoughtSignature()).isEqualTo("U0lHTg==");
    }

    /**
     * 两个流帧：首帧文本带签名，次帧文本无签名（后端常在首 delta 才给签名）⇒
     * 签名必须保留，不被后续缺键覆盖。
     */
    @Test
    void retainsSignatureWhenLaterDeltaOmitsIt() throws Exception {
        var events = collect(
            data("\"content\":{\"parts\":[{\"text\":\"ab\",\"thoughtSignature\":\"U0lHTg==\"}],"
                + "\"role\":\"model\"}"),
            data("\"content\":{\"parts\":[{\"text\":\"cd\"}],\"role\":\"model\"}"),
            finish("STOP"));

        var done = last(events, StreamEvent.StreamDone.class);
        var block = (ContentBlock.TextContent) done.partial().content().get(0);
        assertThat(block.text()).isEqualTo("abcd");
        assertThat(block.textSignature())
            .as("后到 delta 无签名不得擦除已有签名")
            .isEqualTo("U0lHTg==");
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    private List<StreamEvent> collect(String... dataLines) throws Exception {
        String sse = String.join("", dataLines);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        var api = new GoogleGenerativeAiApi(new ApiOptions(
            "http://localhost:" + server.getAddress().getPort(), "test-key",
            Duration.ofSeconds(5), 0, Map.of()));
        var request = StreamRequest.of(ModelId.of("google", "gemini-2.5-flash"),
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

    private static String data(String fields) {
        return "data: {\"candidates\":[{" + fields + "}],"
            + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1}}\n\n";
    }

    private static String finish(String reason) {
        return data("\"finishReason\":\"" + reason + "\"");
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast)
            .reduce((a, b) -> b).orElseThrow(() ->
                new AssertionError("流里没有 " + type.getSimpleName()));
    }
}
