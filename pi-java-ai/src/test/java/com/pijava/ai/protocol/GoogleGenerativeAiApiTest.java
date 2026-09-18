package com.pijava.ai.protocol;

import java.net.InetSocketAddress;
import java.io.OutputStream;
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
 * {@code google-generative-ai} 车道的 <b>B20 stop reason 映射与严格收尾</b>
 * （{@code docs/31 §8.35.14}）。
 *
 * <p>本类此前**不存在**：车道从 Phase 2 起就没被任何夹具覆盖，于是两处偏差一直没人看见 ——
 * 读的是 {@code response} 级访问器（它对缺席**造**一个 {@code FINISH_REASON_UNSPECIFIED}，
 * 见 {@code GenerateContentResponse:352-364}），且读完只 {@code toLowerCase()} 就当成取值
 * 发出去。夹具走真实 SDK 路径（本地 HTTP server 喂线格 SSE），与
 * {@code OpenAICompletionsApiTest} 同一形态。</p>
 */
class GoogleGenerativeAiApiTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 映射（pi google-shared.ts:379-411）
    // ══════════════════════════════════════════════════════════════════

    /**
     * {@code MAX_TOKENS} ⇒ {@code "length"}。
     *
     * <p>宿主 {@code PiLoopRunner} 只在 {@code stopReason == "length"} 时走截断分支
     * （不执行工具、改发续跑提示）—— 旧实现把线格原值小写后直接发出去，这条路径在
     * Google 车道恒不可达。</p>
     */
    @Test
    void maxTokensFinishReasonMapsToLength() throws Exception {
        var events = collect(text("partial"), finish("MAX_TOKENS"));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("length");
    }

    /** {@code STOP} ⇒ {@code "stop"}（正常收尾的**对照面**：旧实现下也是绿的）。 */
    @Test
    void stopFinishReasonMapsToStop() throws Exception {
        var events = collect(text("all done"), finish("STOP"));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("stop");
        assertThat(errors(events)).isEmpty();
    }

    /**
     * ⑨（D5）：{@code rawStopReason} 是**映射前**的线格原值（pi {@code :217}），
     * 也是本车道收尾文案的唯一来源（pi {@code :272-273}）。
     *
     * <p>线格 {@code MAX_TOKENS}（大写、SDK 枚举名）与消息 {@code "length"} 取值不同 ⇒
     * 「从 {@code stopReason} 反推」这种写法在这条上立刻红。</p>
     */
    @Test
    void rawStopReasonKeepsTheUnmappedWireValue() throws Exception {
        var events = collect(text("partial"), finish("MAX_TOKENS"));

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo("length");
        assertThat(done.partial().rawStopReason()).isEqualTo("MAX_TOKENS");
    }

    /**
     * {@code SAFETY} ⇒ error + {@code "Provider stopped with: SAFETY"}。
     *
     * <p>⚠️ 文案**不在**映射里：pi 的 {@code mapStopReason} 对那 15 个取值只返回裸
     * {@code "error"}，文案由收尾处用 {@code rawStopReason} 拼（{@code :272-273}）——
     * 两处分写，别合并。</p>
     */
    @Test
    void safetyFinishReasonIsAnError() throws Exception {
        var events = collect(text("cut"), finish("SAFETY"));

        assertThat(errorMessage(events)).isEqualTo("Provider stopped with: SAFETY");
        assertThat(dones(events)).isEmpty();
    }

    /** {@code FINISH_REASON_UNSPECIFIED} ⇒ error（它在 pi 的 15 个错误取值里，**不是**哨兵）。 */
    @Test
    void unchangedSpecifiedFinishReasonIsAnError() throws Exception {
        var events = collect(text("cut"), finish("FINISH_REASON_UNSPECIFIED"));

        assertThat(errorMessage(events)).isEqualTo("Provider stopped with: FINISH_REASON_UNSPECIFIED");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * {@code NO_IMAGE} ⇒ error + {@code "Provider stopped with: NO_IMAGE"}。
     *
     * <p>⚠️ 这一格同时钉住**读法**：{@code NO_IMAGE} 与 {@code IMAGE_*} 四种**不在**
     * google-genai 的 {@code FinishReason.Known} 里（实测：{@code knownEnum()} 对它静默给
     * {@code FINISH_REASON_UNSPECIFIED}，与真正未知的值**不可区分**、且都不抛）。若按
     * {@code knownEnum()} 读，这条会红成 {@code "Provider stopped with:
     * FINISH_REASON_UNSPECIFIED"} —— 文案不符，而不是事件类型不符。</p>
     */
    @Test
    void noImageFinishReasonIsAnError() throws Exception {
        var events = collect(text("cut"), finish("NO_IMAGE"));

        assertThat(errorMessage(events)).isEqualTo("Provider stopped with: NO_IMAGE");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * SDK 词表之外的取值 ⇒ error + {@code "Unhandled stop reason: BRAND_NEW"}
     * （pi 的 {@code default: throw}）。
     *
     * <p>⚠️ 与 completions 车道**形状相同、行为相反**：那边 pi 的 {@code default} 落 error
     * 事件（不抛），这边 pi 抛。两处都照 pi 写，别「统一」。</p>
     */
    @Test
    void unknownFinishReasonIsAnError() throws Exception {
        var events = collect(text("cut"), finish("BRAND_NEW"));

        assertThat(errorMessage(events)).isEqualTo("Unhandled stop reason: BRAND_NEW");
        assertThat(dones(events)).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // 严格收尾（pi :268-269）与 STOP 的工具块补正（pi :219-220）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 整条流**没有** {@code finishReason} ⇒ error +
     * {@code "Google stream ended without a finish reason"}。
     *
     * <p>⚠️ 修复前这一格**不是**「静默发 stop」而是更糟：{@code response} 级访问器对缺席
     * **造**了一个 {@code FINISH_REASON_UNSPECIFIED} ⇒ 车道把 {@code finish_reason_unspecified}
     * 当成 pi 的 stop reason 发出去（一个 pi 词表里根本不存在的取值）。这条与
     * {@link #unchangedSpecifiedFinishReasonIsAnError}（线格**真的**发了
     * {@code FINISH_REASON_UNSPECIFIED}）在修复前给出**同一个** {@code StreamDone} 取值 ——
     * 「没观测到」与「观测到 UNSPECIFIED」不可区分，正是本包要修的哨兵缺失。</p>
     */
    @Test
    void streamWithoutFinishReasonIsAnError() throws Exception {
        var events = collect(text("hi"));

        assertThat(errorMessage(events)).isEqualTo("Google stream ended without a finish reason");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * 有工具块却是 {@code STOP} ⇒ {@code "tool_use"}（pi {@code :219-220}）。
     *
     * <p>Google 的 {@code STOP} 同时表示「正常收尾」与「调工具收尾」，pi 用「内容里有没有
     * 工具块」把两者分开。⚠️ 工具块与 {@code finishReason} 在**同一帧**里 —— 这正是上面
     * 读点必须在候选体内、且不能早退的原因。</p>
     */
    @Test
    void toolCallWithStopFinishReasonBecomesToolUse() throws Exception {
        var events = collect(data("\"content\":{\"parts\":[{\"functionCall\":"
            + "{\"name\":\"write\",\"args\":{}}}],\"role\":\"model\"},"
            + "\"finishReason\":\"STOP\""));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("tool_use");
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    /**
     * 收一条流的**全部**事件。
     *
     * <p>⚠️ 不走 {@code streamBlocking}：它在 {@code onComplete} 时**无条件**补一条
     * {@code StreamDone("stop", …)}（{@code AbstractChatApi:107-116} 的兜底），
     * 「只有 error、没有 done」这类断言会被它污染。</p>
     */
    private List<StreamEvent> collect(String... dataLines) throws Exception {
        // ⚠️ **不**补 OpenAI 那个 `data: [DONE]` 终止符：Gemini 的 SSE 没有它，
        // google-genai 会去 JSON 解析该行并以 "Failed to parse the JSON string."
        // 收场 —— 那样 8 条夹具会一起红成「解析失败」，看不出任何 stop reason 的差别。
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

    // ── SSE 线格 ────────────────────────────────────────────────────────

    /** 一个候选的完整 data 行；{@code fields} 是候选对象里的字段。 */
    private static String data(String fields) {
        return "data: {\"candidates\":[{" + fields + "}],"
            + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1}}\n\n";
    }

    /** 只有文本、**不带** {@code finishReason} 的帧。 */
    private static String text(String value) {
        return data("\"content\":{\"parts\":[{\"text\":\"" + value + "\"}],\"role\":\"model\"}");
    }

    /** 终帧：只带 {@code finishReason}，**没有** content（Gemini 的真实形状）。 */
    private static String finish(String reason) {
        return data("\"finishReason\":\"" + reason + "\"");
    }

    // ── 断言帮手 ────────────────────────────────────────────────────────

    private static List<StreamEvent> errors(List<StreamEvent> events) {
        return events.stream().filter(StreamEvent.StreamError.class::isInstance).toList();
    }

    private static List<StreamEvent> dones(List<StreamEvent> events) {
        return events.stream().filter(StreamEvent.StreamDone.class::isInstance).toList();
    }

    /**
     * 错误通道**唯一一条**的 {@code getMessage()}。
     *
     * <p>⚠️「有 error」与「**恰**一条 error」是两件事：pi 收尾处是 throw ⇒ 一条流只有
     * 一个终局事件；pi-java 若不记账就会「先 error 后 done」。故这一并钉上。</p>
     */
    private static String errorMessage(List<StreamEvent> events) {
        var errors = errors(events);
        assertThat(errors)
            .as("错误通道应恰有一条事件，实际事件：" + names(events))
            .hasSize(1);
        return ((StreamEvent.StreamError) errors.get(0)).error().getMessage();
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast)
            .reduce((a, b) -> b).orElseThrow(
                () -> new AssertionError("流里没有 " + type.getSimpleName() + "：" + names(events)));
    }

    /** 事件名，{@code StreamDone} 带上取值 —— 红灯里能直接读出线格被翻成了什么。 */
    private static List<String> names(List<StreamEvent> events) {
        return events.stream().map(e -> e instanceof StreamEvent.StreamDone d
            ? "StreamDone(" + d.reason() + ")"
            : e.getClass().getSimpleName()).toList();
    }
}
