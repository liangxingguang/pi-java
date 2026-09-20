package com.pijava.ai.protocol;

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

import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Anthropic 车道的 usage 四分量与计价 —— pi {@code anthropic-messages.ts:602-625}
 * （{@code message_start}）与 {@code :752-787}（{@code message_delta}）的逐条移植
 * （包 H1 步 3，{@code docs/42 §8.3} T2/T3/T4）。
 *
 * <p>本包最易做错的就是这两段，所以每条钉子都对着 pi 的一句注释：</p>
 * <ul>
 *   <li><b>首帧保留</b>（{@code :616-617} 逐字：<i>"This ensures we have input token counts
 *       even if the stream is aborted early"</i>）—— {@code message_start} 五字段
 *       <b>无条件</b> {@code || 0} 写入。</li>
 *   <li><b>逐字段覆盖</b>（{@code :764-765} 逐字：<i>"Only update usage fields if present
 *       (not null)"</i>）—— {@code message_delta} 各自判 {@code != null}，
 *       <b>{@code 0} 是合法值、必须覆盖</b>（这条专打 {@code != null} vs JS 真值判断）。</li>
 *   <li><b>{@code cacheWrite1h} 只在 {@code message_start} 设</b>，delta <b>不更新</b>。</li>
 *   <li><b>{@code reasoning} 只从 delta 的 {@code output_tokens_details.thinking_tokens} 取</b>。</li>
 * </ul>
 */
class AnthropicMessagesApiUsageTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // T2：首帧保留
    // ═══════════════════════════════════════════════════════════

    @Test
    void messageStartUsageSurvivesWhenTheDeltaOmitsInputTokens() throws Exception {
        // delta 只带 output_tokens（真实 relay 的形状）⇒ input 必须保住首帧的 1000
        var usage = lastUsage(collect(
            startWithFullUsage() + delta("{\"stop_reason\":\"end_turn\",\"stop_sequence\":null}",
                "{\"output_tokens\":45}") + messageStop()));

        assertThat(usage.input()).as("首帧的 input 不得被 delta 抹掉").isEqualTo(1000);
        assertThat(usage.cacheRead()).isEqualTo(20);
        assertThat(usage.cacheWrite()).isEqualTo(80);
        assertThat(usage.output()).isEqualTo(45);
    }

    @Test
    void messageStartUsageIsVisibleOnTheTerminalPartialWithoutAnyDelta() throws Exception {
        // pi 那句注释的场景：流**提前中止**、一个 message_delta 都没来。
        // pi 的 output.usage 已被 message_start 就地写过 ⇒ 终局消息仍带 input。
        var events = collect(startWithFullUsage() + messageStop());

        var partial = events.get(events.size() - 1).partial();
        assertThat(partial).as("终局事件必须带 partial").isNotNull();
        assertThat(partial.usage()).as("首帧 usage 必须已经落在 partial 上").isNotNull();
        assertThat(partial.usage().toUsage().input()).isEqualTo(1000);
    }

    // ═══════════════════════════════════════════════════════════
    // T3：`0` 是合法值，必须覆盖（pi 用 `!= null`，不是 JS 真值判断）
    // ═══════════════════════════════════════════════════════════

    @Test
    void zeroInputTokensInTheDeltaOverridesTheFirstFrame() throws Exception {
        var usage = lastUsage(collect(
            startWithFullUsage() + delta("{\"stop_reason\":\"end_turn\",\"stop_sequence\":null}",
                "{\"input_tokens\":0,\"output_tokens\":45}") + messageStop()));

        assertThat(usage.input())
            .as("0 是合法值 —— 若按 JS 真值判断就会错误地保住首帧的 1000")
            .isZero();
    }

    // ═══════════════════════════════════════════════════════════
    // T4：cacheWrite1h 只在 message_start 设
    // ═══════════════════════════════════════════════════════════

    @Test
    void oneHourCacheSplitIsOnlyCapturedFromMessageStart() throws Exception {
        var usage = lastUsage(collect(
            startWithFullUsage() + delta("{\"stop_reason\":\"end_turn\",\"stop_sequence\":null}",
                "{\"output_tokens\":45,\"cache_creation_input_tokens\":90}") + messageStop()));

        assertThat(usage.cacheWrite()).as("delta 的 cache_creation 覆盖 cacheWrite").isEqualTo(90);
        assertThat(usage.cacheWrite1h())
            .as("cacheWrite1h 不在 delta 的覆盖列表里（pi :763-787）")
            .isEqualTo(50.0);
    }

    // ═══════════════════════════════════════════════════════════
    // reasoning：只从 delta 的 output_tokens_details 取
    // ═══════════════════════════════════════════════════════════

    @Test
    void reasoningIsTakenFromTheDeltaOutputTokensDetails() throws Exception {
        var usage = lastUsage(collect(
            startWithFullUsage() + delta("{\"stop_reason\":\"end_turn\",\"stop_sequence\":null}",
                "{\"output_tokens\":45,\"output_tokens_details\":{\"thinking_tokens\":33}}")
                + messageStop()));

        assertThat(usage.reasoning()).isEqualTo(33.0);
    }

    @Test
    void reasoningStaysAbsentWhenOnlyTheFirstFrameReportedIt() throws Exception {
        // 首帧带 output_tokens_details.thinking_tokens，但 pi 的 message_start 段**不读它**
        // ⇒ reasoning 必须仍是 null（缺席 ⇒ @JsonInclude(NON_NULL) 省键，与 pi 同）
        var usage = lastUsage(collect(
            startWithFullUsage() + delta("{\"stop_reason\":\"end_turn\",\"stop_sequence\":null}",
                "{\"output_tokens\":45}") + messageStop()));

        assertThat(usage.reasoning())
            .as("reasoning 只由 delta 的 output_tokens_details 设定（pi :779-782）")
            .isNull();
    }

    // ═══════════════════════════════════════════════════════════
    // totalTokens 重算 + 计价
    // ═══════════════════════════════════════════════════════════

    @Test
    void totalTokensIsRecomputedFromTheFourComponents() throws Exception {
        // Anthropic 不提供 total_tokens ⇒ pi 自算四分量和（:622-624）
        var usage = lastUsage(collect(
            startWithFullUsage() + delta("{\"stop_reason\":\"end_turn\",\"stop_sequence\":null}",
                "{\"output_tokens\":45}") + messageStop()));

        assertThat(usage.totalTokens()).isEqualTo(1000 + 45 + 20 + 80);
    }

    @Test
    void costIsComputedFromTheModelPricing() throws Exception {
        var usage = lastUsage(collect(
            startWithFullUsage() + delta("{\"stop_reason\":\"end_turn\",\"stop_sequence\":null}",
                "{\"output_tokens\":45}") + messageStop()));

        // 价目 input 3 / output 15 / cacheRead 0.3 / cacheWrite 3.75（$/1M）
        // cacheWrite 80，其中 1h 50、5m 30 ⇒ cacheWrite 成本 = (3.75*30 + 3.0*2*50) / 1M
        assertThat(usage.cost().input()).isCloseTo(3.0 * 1000 / 1e6, within(1e-12));
        assertThat(usage.cost().output()).isCloseTo(15.0 * 45 / 1e6, within(1e-12));
        assertThat(usage.cost().cacheRead()).isCloseTo(0.3 * 20 / 1e6, within(1e-12));
        assertThat(usage.cost().cacheWrite())
            .isCloseTo((3.75 * 30 + 3.0 * 2 * 50) / 1e6, within(1e-12));
        assertThat(usage.cost().total()).isGreaterThan(0);
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具脚手架
    // ═══════════════════════════════════════════════════════════

    /** 价目齐全的模型 —— `StreamRequest.of(ModelId, …)` 走 `ModelInfo.minimal`（价未知）⇒ 必须显式给。 */
    private static final ModelInfo PRICED_MODEL = new ModelInfo(
        ModelId.of("anthropic", "claude-sonnet-4"), "Claude Sonnet 4",
        Set.of(ModelCapability.TEXT), 200_000, 8_192, false,
        new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of()));

    private static String startWithFullUsage() {
        return sse("message_start", "{\"type\":\"message_start\",\"message\":{"
            + "\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-sonnet-4\",\"content\":[],"
            + "\"stop_reason\":null,\"stop_sequence\":null,"
            + "\"usage\":{\"input_tokens\":1000,\"output_tokens\":1,"
            + "\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":80,"
            + "\"cache_creation\":{\"ephemeral_1h_input_tokens\":50,\"ephemeral_5m_input_tokens\":30},"
            + "\"output_tokens_details\":{\"thinking_tokens\":7}}}}");
    }

    private static String delta(String deltaJson, String usageJson) {
        return sse("message_delta", "{\"type\":\"message_delta\",\"delta\":" + deltaJson
            + ",\"usage\":" + usageJson + "}");
    }

    private static String messageStop() {
        return sse("message_stop", "{\"type\":\"message_stop\"}");
    }

    private static String sse(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    /** 收一条流的全部事件（照 {@code AnthropicMessagesApiTest} 的脚手架）。 */
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
        api.stream(new StreamRequest(PRICED_MODEL, null,
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), -1, -1, Map.of()),
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

    /** 最后一条 usage 事件归一出的领域对象。 */
    private static Usage lastUsage(List<StreamEvent> events) {
        return events.stream()
            .filter(StreamEvent.UsageInfo.class::isInstance)
            .map(StreamEvent.UsageInfo.class::cast)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("流里没有 usage 事件：" + events))
            .toUsage();
    }
}
