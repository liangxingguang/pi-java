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
 * {@code mistral-conversations} 车道的 <b>B20 stop reason 映射与严格收尾</b>
 * （{@code docs/31 §8.35.14}）。
 *
 * <p>本类此前**不存在**：车道从 Phase 2 起就没被任何夹具覆盖。三处偏差一直没被看见 ——
 * 取值在 {@code processSseData} 里读，却排在那道 {@code if (delta == null) return} 之后
 * （「只有 finish_reason、没有 delta」的终帧整块丢掉）；只翻 {@code tool_calls}→
 * {@code tool_use} 一种，其余原样发出去；局部变量兜底 {@code "stop"} 把「什么都没看到」
 * 伪装成「正常收尾」。夹具走真实 HTTP 路径（本地 server 喂线格 SSE）。</p>
 */
class MistralConversationsApiTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 映射（pi mistral-conversations.ts:926-941）
    // ══════════════════════════════════════════════════════════════════

    /** {@code length} ⇒ {@code "length"}（**对照面**：旧实现下也是绿的）。 */
    @Test
    void lengthFinishReasonMapsToLength() throws Exception {
        var events = collect(text("partial"), finish("length"));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("length");
    }

    /**
     * {@code model_length} ⇒ {@code "length"}（Mistral 的**专有别名**）。
     *
     * <p>宿主 {@code PiLoopRunner} 只在 {@code stopReason == "length"} 时走截断分支 ——
     * 旧实现把这个别名原样发成 {@code "model_length"}（一个 pi 词表里不存在的取值），
     * 这条路径在 Mistral 车道恒不可达。</p>
     */
    @Test
    void modelLengthFinishReasonMapsToLength() throws Exception {
        var events = collect(text("partial"), finish("model_length"));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("length");
    }

    /** {@code stop} ⇒ {@code "stop"}（**对照面**）。 */
    @Test
    void stopFinishReasonMapsToStop() throws Exception {
        var events = collect(text("all done"), finish("stop"));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("stop");
        assertThat(errors(events)).isEmpty();
    }

    /**
     * {@code tool_calls} ⇒ {@code "tool_use"}，且工具块的 {@code ToolCallEnd} 先于终局事件。
     *
     * <p>**对照面**：旧实现已翻这一种。留着是因为它同时钉住「ToolCallEnd 的补发依赖工具块
     * 已折进来」—— 读点若按 pi 挪到 delta 处理**之前**，这条会红成「没有 ToolCallEnd」。</p>
     */
    @Test
    void toolCallsFinishReasonMapsToToolUse() throws Exception {
        var events = collect(
            data("\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call-1\",\"function\":{\"name\":\"write\","
                + "\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}}]}}]"),
            finish("tool_calls"));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("tool_use");
        // ⚠️ `classes()` 把终局渲染成 `StreamDone(取值)`，所以子序列里不能写裸 "StreamDone"。
        assertThat(classes(events)).containsSubsequence("ToolCallStart", "ToolCallEnd",
            "StreamDone(tool_use)");
    }

    /**
     * {@code error} ⇒ error + {@code "Provider stopped with: error"}。
     *
     * <p>⚠️ 旧实现把线格的 {@code "error"} **当成 stop reason 发成 {@code done("error")}** ——
     * 「done = 成功」这条协议不变量被破。文案在**映射里**（pi {@code :937}），与 Google 车道
     * 「映射只给裸 error、文案在收尾拼」的形状**相反**。</p>
     */
    @Test
    void errorFinishReasonIsAnError() throws Exception {
        var events = collect(text("cut"), finish("error"));

        assertThat(errorMessage(events)).isEqualTo("Provider stopped with: error");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * 未知取值 ⇒ error + {@code "Provider stopped with: X"}（pi 的 {@code default}，**不抛**）。
     *
     * <p>⚠️ 同一条 pi 的 {@code default}：Anthropic 车道是 throw、Google 与 completions 是
     * error 事件、Mistral 也是 error 事件但**自带文案**。四处都照 pi 写，别「统一」。</p>
     */
    @Test
    void unknownFinishReasonIsAnError() throws Exception {
        var events = collect(text("cut"), finish("brand_new_reason"));

        assertThat(errorMessage(events)).isEqualTo("Provider stopped with: brand_new_reason");
        assertThat(dones(events)).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // 读取位置（delta 守卫）与严格收尾（pi :154-155）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 终帧**只有 {@code finish_reason}、没有 {@code delta}** ⇒ 取值照样要读到。
     *
     * <p>⚠️ 旧实现把取值读在 {@code if (delta == null) return} **之后** ⇒ 这种帧的取值被
     * 整块丢掉，静默退化成 {@code "stop"}。pi 的读点在 {@code delta} 之前
     * （{@code :613-619}），没有这道守卫。</p>
     */
    @Test
    void finishReasonWithoutDeltaIsStillRead() throws Exception {
        var events = collect(data("\"choices\":[{\"index\":0,\"finish_reason\":\"length\"}]"));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("length");
    }

    /**
     * 整条流**没有** {@code finish_reason} ⇒ error +
     * {@code "Mistral stream ended without a finish reason"}。
     *
     * <p>⚠️ 修复前这一格是「静默当成正常结束」：局部变量初值 {@code "stop"} 让
     * 「一个取值都没观测到」与「观测到 {@code stop}」不可区分。另注意
     * {@code [DONE]} 此前是**提前 {@code emitDone} + return**，收尾判定整段跑不到 ——
     * 这条夹具同时钉住「[DONE] 只结束循环、不结束判定」。</p>
     */
    @Test
    void streamWithoutFinishReasonIsAnError() throws Exception {
        var events = collect(text("hi"));

        assertThat(errorMessage(events)).isEqualTo("Mistral stream ended without a finish reason");
        assertThat(dones(events)).isEmpty();
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

    // ── SSE 线格 ────────────────────────────────────────────────────────

    /** 一个完整 data 行；{@code json} 是响应对象**内部**的字段串。 */
    private static String data(String json) {
        return "data: {" + json + "}\n\n";
    }

    private static String text(String value) {
        return data("\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + value + "\"}}]");
    }

    /** 终帧：{@code delta} 为空对象（Mistral 的真实形状），只带 {@code finish_reason}。 */
    private static String finish(String reason) {
        return data("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + reason + "\"}]");
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
    private static List<String> classes(List<StreamEvent> events) {
        return events.stream().map(e -> e instanceof StreamEvent.StreamDone d
            ? "StreamDone(" + d.reason() + ")"
            : e.getClass().getSimpleName()).toList();
    }

    private static List<String> names(List<StreamEvent> events) {
        return classes(events);
    }
}
