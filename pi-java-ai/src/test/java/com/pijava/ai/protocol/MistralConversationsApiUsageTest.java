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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Mistral 车道的 usage 归一 —— pi {@code mistral-conversations.ts:536-555}
 * （{@code getMistralCachedPromptTokens}）与 {@code :596-611} 的逐条移植
 * （包 H1 步 5，{@code docs/42 §2.1 P14/P15}，测试计划 T9）。
 *
 * <p>容易做错的：</p>
 * <ul>
 *   <li><b>cacheRead 是六路 {@code ??} 链</b>：驼峰/下划线 × 两种容器名（Details/Detail）
 *       ＋ 两个顶层 {@code num*}。{@code ??} 不是 {@code ||} ⇒ <b>显式的 0 短路后续来源</b>。</li>
 *   <li><b>typeof + isFinite 门在链之后</b>（{@code :552}）：链命中一个字符串也算命中
 *       ⇒ 门把它归 0，<b>不</b>回落到下一来源。</li>
 *   <li><b>双重钳位</b> {@code Math.min(promptTokens, Math.max(0, cached))}（{@code :553}）。</li>
 *   <li><b>totalTokens 优先 provider、兜底自算</b>；{@code reasoning} <b>从不设置</b>（P15）
 *       ⇒ null，与 completions/responses 车道的「恒为数字」相反。</li>
 *   <li>pi 的 usage 处理在「choices 空帧早退」<b>之前</b>（{@code :596} 先于 {@code :613}）
 *       —— Mistral 真会把 usage 放在终局 {@code choices: []} 帧上。</li>
 * </ul>
 */
class MistralConversationsApiUsageTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // T9：六路 ?? 链
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest(name = "来源 {0}")
    @ValueSource(strings = {
        "\"promptTokensDetails\":{\"cachedTokens\":400}",
        "\"prompt_tokens_details\":{\"cached_tokens\":400}",
        "\"promptTokenDetails\":{\"cachedTokens\":400}",
        "\"prompt_token_details\":{\"cached_tokens\":400}",
        "\"numCachedTokens\":400",
        "\"num_cached_tokens\":400",
    })
    void everyChainSourceFeedsCacheRead(String cachedField) throws Exception {
        var usage = lastUsage(collect(usageFrame(
            "\"prompt_tokens\":1000,\"completion_tokens\":50," + cachedField + ",\"total_tokens\":1050")));

        assertThat(usage.cacheRead()).isEqualTo(400);
        assertThat(usage.input()).as("减法：1000 − 400").isEqualTo(600);
    }

    @Test
    void explicitZeroInAnEarlierSourceShortCircuitsTheChain() throws Exception {
        // pi 用 `??`：第一源显式为 0 ⇒ 不得回落到后面的 300。写成 `||` 就会错取 300。
        var usage = lastUsage(collect(usageFrame(
            "\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"promptTokensDetails\":{\"cachedTokens\":0},\"num_cached_tokens\":300")));

        assertThat(usage.cacheRead()).as("`??` 的短路语义").isZero();
        assertThat(usage.input()).isEqualTo(1000);
    }

    @Test
    void nonNumericHitIsZeroedAndDoesNotFallThrough() throws Exception {
        // 链在 `promptTokensDetails.cachedTokens` 就命中了（字符串也是非 null），
        // typeof/isFinite 门在链之后 ⇒ 归 0，不回落 num_cached_tokens。
        var usage = lastUsage(collect(usageFrame(
            "\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"promptTokensDetails\":{\"cachedTokens\":\"400\"},\"num_cached_tokens\":300")));

        assertThat(usage.cacheRead()).isZero();
    }

    // ═══════════════════════════════════════════════════════════
    // T9：双重钳位
    // ═══════════════════════════════════════════════════════════

    @Test
    void cachedIsClampedUpToZeroWhenNegative() throws Exception {
        var usage = lastUsage(collect(usageFrame(
            "\"prompt_tokens\":100,\"completion_tokens\":5,\"num_cached_tokens\":-50")));

        assertThat(usage.cacheRead()).as("Math.max(0, …)").isZero();
        assertThat(usage.input()).isEqualTo(100);
    }

    @Test
    void cachedIsClampedDownToPromptTokensWhenOver() throws Exception {
        // cached 400 > prompt 100 ⇒ Math.min(promptTokens, …) ⇒ cacheRead 只到 100，
        // input = max(0, 100 − 100) = 0。
        var usage = lastUsage(collect(usageFrame(
            "\"prompt_tokens\":100,\"completion_tokens\":5,\"num_cached_tokens\":400")));

        assertThat(usage.cacheRead()).as("Math.min(promptTokens, …)").isEqualTo(100);
        assertThat(usage.input()).isZero();
    }

    // ═══════════════════════════════════════════════════════════
    // P15：totalTokens 优先 provider、兜底自算；reasoning 从不设置
    // ═══════════════════════════════════════════════════════════

    @Test
    void totalTokensPrefersTheProviderValue() throws Exception {
        var usage = lastUsage(collect(usageFrame(
            "\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"num_cached_tokens\":400,\"total_tokens\":999")));

        assertThat(usage.totalTokens()).isEqualTo(999);
    }

    @Test
    void totalTokensFallsBackToSelfSumWhenProviderReportsNone() throws Exception {
        // 无 total_tokens ⇒ 自算 input + output + cacheRead + cacheWrite = 600+50+400+0
        var usage = lastUsage(collect(usageFrame(
            "\"prompt_tokens\":1000,\"completion_tokens\":50,\"num_cached_tokens\":400")));

        assertThat(usage.totalTokens()).as("falsy 兜底自算").isEqualTo(1050);
    }

    @Test
    void reasoningIsNeverReportedByThisLane() throws Exception {
        // P15：pi 从不给本车道的 reasoning 赋值 ⇒ 保持 undefined ≙ null。
        // （与 completions/responses 的 `|| 0` 恒数字相反，别「统一」。）
        var usage = lastUsage(collect(usageFrame(
            "\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15")));

        assertThat(usage.reasoning()).isNull();
    }

    // ═══════════════════════════════════════════════════════════
    // 位置：usage 先于「choices 空帧」早退（pi :596 先于 :613）
    // ═══════════════════════════════════════════════════════════

    @Test
    void usageOnTheTerminalEmptyChoicesFrameIsStillReported() throws Exception {
        // Mistral 的真实终帧：choices: [] + usage。早退在前就会整帧丢失。
        var usage = lastUsage(collect(
            finishFrame("stop"),
            data("\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"total_tokens\":1050}")));

        assertThat(usage.input()).isEqualTo(1000);
        assertThat(usage.output()).isEqualTo(50);
    }

    // ═══════════════════════════════════════════════════════════
    // 计价
    // ═══════════════════════════════════════════════════════════

    @Test
    void costIsComputedFromTheModelPricing() throws Exception {
        var usage = lastUsage(collect(usageFrame(
            "\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"num_cached_tokens\":400,\"total_tokens\":1050")));

        // 价目 input 3 / output 15 / cacheRead 0.3 / cacheWrite 3.75（$/1M）
        assertThat(usage.cost().input()).isCloseTo(3.0 * 600 / 1e6, within(1e-15));
        assertThat(usage.cost().output()).isCloseTo(15.0 * 50 / 1e6, within(1e-15));
        assertThat(usage.cost().cacheRead()).isCloseTo(0.3 * 400 / 1e6, within(1e-15));
        assertThat(usage.cost().cacheWrite()).isZero();
        assertThat(usage.cost().total()).isGreaterThan(0);
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具脚手架
    // ═══════════════════════════════════════════════════════════

    private static final ModelInfo PRICED_MODEL = new ModelInfo(
        ModelId.of("mistral", "mistral-large-latest"), "mistral-large-latest",
        Set.of(ModelCapability.TEXT), 128_000, 16_384, false,
        new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of()));

    private static String data(String json) {
        return "data: {" + json + "}\n\n";
    }

    /** 终帧带 finish_reason（过严格收尾）＋ 该帧的 usage。 */
    private static String usageFrame(String usageJson) {
        return data("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{" + usageJson + "}");
    }

    private static String finishFrame(String reason) {
        return data("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + reason + "\"}]");
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
        var request = new StreamRequest(PRICED_MODEL, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
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
        assertThat(finished.await(10, TimeUnit.SECONDS)).as("流未在 10s 内结束").isTrue();
        return List.copyOf(events);
    }

    private static Usage lastUsage(List<StreamEvent> events) {
        return events.stream()
            .filter(StreamEvent.UsageInfo.class::isInstance)
            .map(StreamEvent.UsageInfo.class::cast)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("流里没有 usage 事件：" + events))
            .toUsage();
    }
}
