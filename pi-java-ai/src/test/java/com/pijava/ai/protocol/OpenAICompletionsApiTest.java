package com.pijava.ai.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code openai-completions} 车道：请求侧构造 + <b>B20 的 stop reason 映射与严格收尾</b>。
 *
 * <p>B20 夹具（{@code docs/31 §8.35.14} 第八节）此前**一条都没有**：本类原来只有一个
 * {@code buildParams} 用例，所以「收尾固定发 {@code toolCall.started() ? "tool_use" :
 * "stop"}」这一处从 Phase 2 起就没人碰过 —— 车道**完全不读** {@code choice.finish_reason}。</p>
 *
 * <p>pi 的收尾是**五段判**（{@code openai-completions.ts:678-695}）：abort → aborted →
 * 容忍分支 → error → 严格分支 → done。本类逐个钉住 pi-java 可达的那几段。</p>
 *
 * <p><b>夹具走真实 SDK 路径</b>（本地 HTTP server 喂线格 SSE），与
 * {@code OpenAICompletionsReasoningTest} 同一形态。</p>
 */
class OpenAICompletionsApiTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 请求侧（既有夹具）
    // ══════════════════════════════════════════════════════════════════

    @Test
    void buildParamsPassesToolsAndRequestsUsage() throws Exception {
        var api = new OpenAICompletionsApi(
            new ApiOptions("", "test-key", Duration.ofSeconds(10), 1, Map.of()),
            "OPENAI_API_KEY");
        var request = new StreamRequest(
            ModelId.of("deepseek", "deepseek-v4-flash"),
            null,
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(new ToolDefinition(
                "write", "Write a file",
                Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string"))))),
            100, 0.5, Map.of());

        // 拆文件提交后 buildParams 住在 OpenAICompletionsMessageConverter（同包 package-private static）。
        var params = OpenAICompletionsMessageConverter.buildParams(
            request, "openai-completions", "https://api.openai.com/v1");

        var tools = params.tools().orElseThrow();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).isFunction()).isTrue();
        assertThat(params.streamOptions().orElseThrow()
            .includeUsage().orElse(false)).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════
    // B20：stop reason 映射（pi mapStopReason :1550-1571）
    // ══════════════════════════════════════════════════════════════════

    /**
     * {@code finish_reason:"length"} ⇒ {@code "length"}。
     *
     * <p>这是 B20 里**后果最重**的一格：宿主层 {@code PiLoopRunner} 只在
     * {@code stopReason == "length"} 时才走截断分支（不执行工具、改发续跑提示）——
     * 取值丢了这一整条路径在 pi-java 里恒不可达。</p>
     */
    @Test
    void lengthFinishReasonMapsToLength() throws Exception {
        var events = collect(stream(chunk("{\"content\":\"partial a\"}"), finishChunk("length")));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("length");
    }

    /**
     * {@code finish_reason:"tool_calls"} ⇒ {@code "tool_use"}（pi 的 {@code toolUse}）。
     *
     * <p>⚠️ 这条的**独立性**在于线格里没有任何 tool_call 块：旧实现的取值只由
     * 「有没有工具块」决定，故它在旧实现下会给出 {@code "stop"} 而不是 {@code "tool_use"}。</p>
     */
    @Test
    void toolCallsFinishReasonMapsToToolUseEvenWithoutToolBlocks() throws Exception {
        var events = collect(stream(chunk("{\"content\":\"done\"}"), finishChunk("tool_calls")));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("tool_use");
    }

    /**
     * ⑨（D5）：{@code rawStopReason} 是**映射前**的线格原值（pi {@code :572}）。
     *
     * <p>线格 {@code tool_calls} 与消息 {@code "tool_use"} 取值不同（后者是 pi-java 的词表，
     * 见 {@code PiMessagesApi:244-246}）⇒「从 {@code stopReason} 反推」在这条上立刻红。</p>
     */
    @Test
    void rawStopReasonKeepsTheUnmappedWireValue() throws Exception {
        var events = collect(stream(chunk("{\"content\":\"done\"}"), finishChunk("tool_calls")));

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo("tool_use");
        assertThat(done.partial().rawStopReason()).isEqualTo("tool_calls");
    }

    /**
     * {@code finish_reason:"content_filter"} ⇒ error + {@code "Provider finish_reason:
     * content_filter"}（pi {@code :1562-1563}）。
     */
    @Test
    void contentFilterFinishReasonIsAnError() throws Exception {
        var events = collect(stream(chunk("{\"content\":\"cut\"}"), finishChunk("content_filter")));

        assertThat(errorMessage(events)).isEqualTo("Provider finish_reason: content_filter");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * {@code finish_reason:"network_error"} ⇒ error + {@code "Provider finish_reason:
     * network_error"}（pi {@code :1564-1565}）。
     *
     * <p>⚠️ 这一格同时钉住**读法**：{@code network_error} **不在** SDK 的
     * {@code FinishReason.Known} 里（只有 stop/length/tool_calls/content_filter/
     * function_call），{@code known()} 对它**抛** {@code OpenAIInvalidDataException}
     * （实测 4.42.0）；只有 {@code asString()} 给出线格原值。用错读法这条会红成
     * 「流没结束 / 抛异常」而不是文案不符。</p>
     */
    @Test
    void networkErrorFinishReasonIsAnError() throws Exception {
        var events = collect(stream(chunk("{\"content\":\"cut\"}"), finishChunk("network_error")));

        assertThat(errorMessage(events)).isEqualTo("Provider finish_reason: network_error");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * SDK 与 pi 都不认识的取值 ⇒ error + {@code "Provider finish_reason: X"}
     * （pi {@code :1566-1571} 的 {@code default}）。
     *
     * <p>⚠️ 与同期的 Anthropic 车道**形状相同、行为相反**：那边 pi 的 {@code default} 是
     * <b>throw</b>，这边 pi 的 {@code default} 落 <b>error 事件</b>（不抛）。两处都照 pi 写，
     * 别「统一」。</p>
     */
    @Test
    void unknownFinishReasonLandsOnTheErrorChannel() throws Exception {
        var events = collect(stream(chunk("{\"content\":\"cut\"}"), finishChunk("brand_new_reason")));

        assertThat(errorMessage(events)).isEqualTo("Provider finish_reason: brand_new_reason");
        assertThat(dones(events)).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // B20：严格收尾（裁决 D1，pi :685-693）
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>D1 严格版</b>：整条流**没有** {@code finish_reason} ⇒ error +
     * {@code "Stream ended without finish_reason"}（pi {@code :691-693}）。
     *
     * <p>请求带 {@link ModelCompat#NONE}（「models.json 没写 compat」的生产形状）——
     * 该记录的 {@code supportsFinishReason} 探测默认是 {@code true}
     * （{@code detectCompat:1638} 是常量），故这一格就是**绝大多数 relay 的实际走法**。</p>
     *
     * <p>修复前这一格是「静默当成正常结束」：位置在 {@code finish_reason:null}，
     * 车道连「有没有观测到」都不区分。</p>
     */
    @Test
    void streamWithoutFinishReasonIsAnError() throws Exception {
        var events = collect(ModelCompat.NONE,
            stream(chunkWithoutFinishReason("{\"content\":\"hi\"}")));

        assertThat(errorMessage(events)).isEqualTo("Stream ended without finish_reason");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * <b>容忍版的对照面</b>：同一条流 + 显式 {@code compat.supportsFinishReason=false}
     * ⇒ **不报错**，落 {@code "stop"}（pi {@code :685-687} 的
     * {@code toolCall ? "toolUse" : "stop"}）。
     *
     * <p>⚠️ 这条与上一条**只差 compat 那一位**，两条合起来才把「严格/容忍由谁决定」
     * 钉成可测事实 —— 单看这一条的话，旧实现（从不判定）**也是绿的**，它测不出任何东西。
     * 这正是 §8.34.4 决策 3 的「两态」写法，方向相反：那里是「缺席≙false」，
     * 这里是「缺席≙true」。</p>
     */
    @Test
    void explicitlyDisabledFinishReasonSupportEndsNormally() throws Exception {
        var events = collect(new ModelCompat(false, null, false),
            stream(chunkWithoutFinishReason("{\"content\":\"hi\"}")));

        assertThat(last(events, StreamEvent.StreamDone.class).reason()).isEqualTo("stop");
        assertThat(errors(events)).isEmpty();
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    /** 无模型元数据的请求 ⇒ compat 缺席 ⇒ 严格版（生产里没有这种请求，钉的是空值兜底）。 */
    private List<StreamEvent> collect(String sseBody) throws Exception {
        return collect(null, sseBody);
    }

    /**
     * 收一条流的**全部**事件。
     *
     * <p>⚠️ 不走 {@code streamBlocking}：它在 {@code onComplete} 时**无条件**补一条
     * {@code StreamDone("stop", …)}（{@code AbstractChatApi:107-116} 的兜底），
     * 「只有 error、没有 done」这类断言会被它污染（永远看得到一条 done）。</p>
     *
     * @param compat 请求携带的 compat，{@code null} 表示请求不带模型元数据
     * @param sseBody 线格
     */
    private List<StreamEvent> collect(ModelCompat compat, String sseBody) throws Exception {
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
        var api = new OpenAICompletionsApi(new ApiOptions(
            "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key",
            Duration.ofSeconds(5), 0, Map.of()), "OPENAI_API_KEY");

        var messages = List.<Message>of(new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi"))));
        var request = compat == null
            ? StreamRequest.of(ModelId.of("openai", "glm-5.3-flash"), messages)
            : new StreamRequest(modelInfo(compat), null, messages, List.of(), -1, -1, Map.of());

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

    private static ModelInfo modelInfo(ModelCompat compat) {
        return new ModelInfo(ModelId.of("openai", "glm-5.3-flash"), "glm-5.3-flash",
            Set.of(), 0, 0, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), compat);
    }

    // ── SSE 线格 ────────────────────────────────────────────────────────

    private static String stream(String... chunks) {
        return String.join("", chunks) + "data: [DONE]\n\n";
    }

    /** 一个 delta 帧；{@code finish_reason} 为 null（终帧用 {@link #finishChunk}）。 */
    private static String chunk(String deltaJson) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
            + "\"delta\":" + deltaJson + ",\"finish_reason\":null}]}\n\n";
    }

    /**
     * 一个**不带** {@code finish_reason} 键的 delta 帧 —— 有些 relay 整条流都不发这个字段
     * （这正是 D1 严格版要抓的形状；{@code finish_reason:null} 与缺席在 SDK 侧同为
     * {@code Optional.empty()}，pi 侧同为真值假）。
     */
    private static String chunkWithoutFinishReason(String deltaJson) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
            + "\"delta\":" + deltaJson + "}]}\n\n";
    }

    /** 终帧：空 delta + {@code finish_reason}。 */
    private static String finishChunk(String finishReason) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
            + "\"delta\":{},\"finish_reason\":\"" + finishReason + "\"}]}\n\n";
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
     * 一个终局事件；pi-java 若不记账就会「先 error 后 done」。故这里一并把「恰一条」钉上。</p>
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
