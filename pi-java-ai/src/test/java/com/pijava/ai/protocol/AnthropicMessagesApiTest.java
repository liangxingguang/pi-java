package com.pijava.ai.protocol;

import java.io.IOException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Anthropic 车道：Phase 6 的构造器行为 + <b>B20 的 stop reason 映射与收尾</b>。
 *
 * <p>B20 夹具（{@code docs/31 §8.35.14} 第八节）此前**一条都没有**：本类原来只有两个构造器
 * 用例，所以「{@code :94} 无条件 {@code emitDone("end_turn")}」这一处从 Phase 2 起就没人
 * 碰过。pi 的收尾是**四段判**（{@code anthropic-messages.ts:779-804}）：abort → pending →
 * error/aborted → done；pi-java 只有最后一段，且取值是**车道自己编的**。</p>
 */
class AnthropicMessagesApiTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── Phase 6：构造器 ─────────────────────────────────────────────────

    @Test
    void customEnvVarAppearsInMissingKeyError() {
        var options = new ApiOptions(
            "https://api.minimaxi.com/anthropic", "",
            Duration.ofSeconds(1), 0, Map.of());
        assertThatThrownBy(() -> new AnthropicMessagesApi(options, "MINIMAX_CN_API_KEY"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MINIMAX_CN_API_KEY");
    }

    @Test
    void acceptsExplicitKeyAndBaseUrlOverride() {
        var options = new ApiOptions(
            "https://api.minimaxi.com/anthropic", "sk-test",
            Duration.ofSeconds(1), 0, Map.of());
        var api = new AnthropicMessagesApi(options, "MINIMAX_CN_API_KEY");
        assertThat(api).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════════
    // B20：stop reason 映射与收尾（docs/31 §8.35.14 第二/三节）
    // ══════════════════════════════════════════════════════════════════

    /**
     * {@code max_tokens} ⇒ {@code "length"}（pi {@code anthropic-messages.ts:1470-1471}）。
     *
     * <p>修复前车道无视 {@code message_delta.stop_reason}，一律发 {@code "end_turn"} ——
     * 「被 max_tokens 截断」这条对宿主层的 {@code length} 门（{@code PiLoopTools} 不执行工具）
     * 是**不可见**的。</p>
     */
    @Test
    void maxTokensMapsToLength() throws Exception {
        var events = collect(streamWithDelta("{\"stop_reason\":\"max_tokens\",\"stop_sequence\":null}"));

        assertThat(errors(events))
            .as("max_tokens 是正常结束的一种（截断），不是错误")
            .isEmpty();
        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("length");
    }

    /**
     * ⑨（D5）：{@code rawStopReason} 是**映射前**的线格原值（pi {@code :744}）。
     *
     * <p>取值刻意选一个与映射结果**不同**的（线格 {@code max_tokens} ⇒ 消息
     * {@code length}）：若某天有人把它写成「从 {@code stopReason} 反推」，这条立刻红。</p>
     */
    @Test
    void rawStopReasonKeepsTheUnmappedWireValue() throws Exception {
        var events = collect(streamWithDelta("{\"stop_reason\":\"max_tokens\",\"stop_sequence\":null}"));

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo("length");
        assertThat(done.partial().rawStopReason()).isEqualTo("max_tokens");
    }

    /**
     * {@code refusal} ⇒ {@code error} + 文案取 {@code stop_details.explanation}
     * （pi {@code :1472-1476} 的 {@code stopDetails?.explanation || "The model refused…"}）。
     */
    @Test
    void refusalCarriesStopDetailsExplanation() throws Exception {
        var events = collect(streamWithDelta("{\"stop_reason\":\"refusal\","
            + "\"stop_details\":{\"type\":\"refusal\",\"explanation\":\"safety policy\"}}"));

        assertThat(errorMessage(events)).isEqualTo("safety policy");
        assertThat(dones(events))
            .as("pi 在收尾处是 throw ⇒ 一条流【只有一个】终局事件；修复前是 error 后照样 done")
            .isEmpty();
    }

    /**
     * {@code end_turn} ⇒ {@code "stop"}（裁决 D2）。
     *
     * <p>D2 的理由：pi 的 Anthropic 转录里**每一条**都是 {@code "stop"}，pi-java 是
     * {@code "end_turn"} ⇒ 每一条都不同。</p>
     */
    @Test
    void endTurnMapsToStop() throws Exception {
        var events = collect(streamWithDelta("{\"stop_reason\":\"end_turn\",\"stop_sequence\":null}"));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("stop");
    }

    /** {@code sensitive} ⇒ error + 固定文案（pi {@code :1479-1480}）。 */
    @Test
    void sensitiveStopsWithProviderMessage() throws Exception {
        var events = collect(streamWithDelta("{\"stop_reason\":\"sensitive\"}"));

        assertThat(errorMessage(events)).isEqualTo("Provider stopped with: sensitive");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * 未知 stop reason ⇒ error + {@code "Unhandled stop reason: X"}
     * （pi {@code :1481-1483} 的 {@code default: throw}）。
     *
     * <p>⚠️ 这条与 Responses 车道的 α 是**同形**的：两处的 {@code default} 在 pi 都是
     * <b>throw</b>，都被 pi-java 写成了兜底。pi 与 pi-java 的差别在于 throw 的**去处** ——
     * pi 抛穿整条流、pi-java 落进本车道的 {@code catch} 变成 {@code StreamError}，
     * 故断言打在错误通道的文案上。</p>
     */
    @Test
    void unknownStopReasonLandsOnTheErrorChannel() throws Exception {
        var events = collect(streamWithDelta("{\"stop_reason\":\"brand_new_reason\"}"));

        assertThat(errorMessage(events)).isEqualTo("Unhandled stop reason: brand_new_reason");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * 整条流**没有** {@code stop_reason} ⇒ error + {@code "Anthropic stream ended without a
     * stop reason"}（pi {@code :783-785} 的 {@code "pending"} 哨兵）。
     *
     * <p>修复前这一格是「静默当成正常结束」：车道连「有没有观测到 stop reason」都不区分。</p>
     */
    @Test
    void streamWithoutStopReasonIsAnError() throws Exception {
        var events = collect(streamWithDelta("{}"));

        assertThat(errorMessage(events))
            .isEqualTo("Anthropic stream ended without a stop reason");
        assertThat(dones(events)).isEmpty();
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    /**
     * 收一条流的**全部**事件。
     *
     * <p>⚠️ 不走 {@code streamBlocking}：它在 {@code onComplete} 时**无条件**补一条
     * {@code StreamDone("stop", …)}（{@code AbstractChatApi:107-116} 的兜底），
     * 「只有 error、没有 done」这类断言会被它污染（永远看得到一条 done）。</p>
     */
    private List<StreamEvent> collect(String sseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        var api = new AnthropicMessagesApi(new ApiOptions(
            "http://localhost:" + server.getAddress().getPort(), "test-key",
            Duration.ofSeconds(5), 0, Map.of()));

        var events = new CopyOnWriteArrayList<StreamEvent>();
        var finished = new CountDownLatch(1);
        api.stream(StreamRequest.of(ModelId.of("anthropic", "claude-sonnet-4"),
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))))),
            ApiOptions.defaults()).subscribe(new Flow.Subscriber<>() {
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

    /** 一条正常形状的流，只有 {@code message_delta.delta} 由参数给定。 */
    private static String streamWithDelta(String deltaJson) {
        return sse("message_start", "{\"type\":\"message_start\",\"message\":{"
                + "\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4\",\"content\":[],"
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
            + sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}")
            + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + sse("message_delta", "{\"type\":\"message_delta\",\"delta\":" + deltaJson
                + ",\"usage\":{\"output_tokens\":15}}")
            + sse("message_stop", "{\"type\":\"message_stop\"}");
    }

    private static String sse(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

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
     * 一个终局事件；而 pi-java 的 {@code mapEvent} 会把异常**转成事件**、循环继续说下去
     * （未知 stop reason 那条正是走这条路），收尾若不记账就会再补一条「没有 stop reason」。
     * 故这里一并把「恰一条」钉上 —— 否则「多出来的那条也是 error」不会被任何断言发现。</p>
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

    private static List<String> names(List<StreamEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList();
    }
}
