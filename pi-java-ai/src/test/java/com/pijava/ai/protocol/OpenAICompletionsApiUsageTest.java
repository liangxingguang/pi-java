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
 * OpenAI-completions 车道的 usage 归一 —— pi {@code openai-completions.ts:1509-1550}
 * {@code parseChunkUsage} 的逐条移植（包 H1 步 4，{@code docs/42 §8.3} T5/T6/T7）。
 *
 * <p>三条容易做错的：</p>
 * <ul>
 *   <li><b>cacheRead 是三路 {@code ??} 链</b>（{@code :1521-1522}）：OpenAI/OpenRouter 用
 *       {@code prompt_tokens_details.cached_tokens}、DeepSeek 用 {@code prompt_cache_hit_tokens}、
 *       Kimi 在**顶层** {@code cached_tokens}。{@code ??} 不是 {@code ||} ⇒ <b>显式的 {@code 0}
 *       会短路后续来源</b>（下面有一条专钉这个）。</li>
 *   <li><b>减法</b>（{@code :1536}）：{@code input = Math.max(0, prompt_tokens − cacheRead − cacheWrite)}。
 *       注释逐字：<i>"Do not subtract writes from cached_tokens, otherwise spec-compliant providers
 *       are under-reported."</i> ⇒ cacheWrite 从 prompt_tokens 里减，但**不从 cacheRead 里减**。</li>
 *   <li><b>{@code choice.usage} 回退</b>（{@code :566-573}）：Moonshot 型 relay 把 usage 放在
 *       choice 里而非顶层 chunk。条件同时要求 {@code !chunk.usage} 与 {@code choice} 存在。</li>
 * </ul>
 */
class OpenAICompletionsApiUsageTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // T5：减法
    // ═══════════════════════════════════════════════════════════

    @Test
    void cacheReadAndCacheWriteAreBothSubtractedFromPromptTokens() throws Exception {
        var usage = lastUsage(collect(stream(usageChunk(
            "{\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":400,\"cache_write_tokens\":100}}"))));

        assertThat(usage.input()).isEqualTo(500);
        assertThat(usage.cacheRead()).isEqualTo(400);
        assertThat(usage.cacheWrite()).isEqualTo(100);
        assertThat(usage.output()).isEqualTo(50);
        assertThat(usage.totalTokens()).isEqualTo(500 + 50 + 400 + 100);
    }

    @Test
    void inputIsClampedAtZeroWhenCachedExceedsPromptTokens() throws Exception {
        var usage = lastUsage(collect(stream(usageChunk(
            "{\"prompt_tokens\":100,\"completion_tokens\":5,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":400}}"))));

        assertThat(usage.input()).as("Math.max(0, …) 钳位").isZero();
        assertThat(usage.cacheRead()).isEqualTo(400);
    }

    // ═══════════════════════════════════════════════════════════
    // cacheRead 的三路 ?? 链
    // ═══════════════════════════════════════════════════════════

    @Test
    void cacheReadFallsBackToPromptCacheHitTokens() throws Exception {
        // DeepSeek 形状：顶层 prompt_cache_hit_tokens
        var usage = lastUsage(collect(stream(usageChunk(
            "{\"prompt_tokens\":1000,\"completion_tokens\":5,\"prompt_cache_hit_tokens\":300}"))));

        assertThat(usage.cacheRead()).isEqualTo(300);
        assertThat(usage.input()).isEqualTo(700);
    }

    @Test
    void cacheReadFallsBackToTopLevelCachedTokens() throws Exception {
        // Kimi 形状：顶层 cached_tokens（在终局 usage chunk 上）
        var usage = lastUsage(collect(stream(usageChunk(
            "{\"prompt_tokens\":1000,\"completion_tokens\":5,\"cached_tokens\":250}"))));

        assertThat(usage.cacheRead()).isEqualTo(250);
    }

    @Test
    void explicitZeroCachedTokensShortCircuitsTheFallbackChain() throws Exception {
        // pi 用 `??` 不是 `||`：prompt_tokens_details.cached_tokens **显式存在且为 0**
        // ⇒ 不得继续回落到 prompt_cache_hit_tokens。写成 `||` 就会错误地取到 300。
        var usage = lastUsage(collect(stream(usageChunk(
            "{\"prompt_tokens\":1000,\"completion_tokens\":5,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0},\"prompt_cache_hit_tokens\":300}"))));

        assertThat(usage.cacheRead()).as("`??` 的短路语义").isZero();
        assertThat(usage.input()).isEqualTo(1000);
    }

    // ═══════════════════════════════════════════════════════════
    // reasoning：本车道恒为数字（pi 用 `|| 0`）
    // ═══════════════════════════════════════════════════════════

    @Test
    void reasoningTokensComeFromCompletionTokensDetails() throws Exception {
        var usage = lastUsage(collect(stream(usageChunk(
            "{\"prompt_tokens\":10,\"completion_tokens\":50,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":33}}"))));

        assertThat(usage.reasoning()).isEqualTo(33.0);
    }

    @Test
    void reasoningDefaultsToZeroRatherThanAbsent() throws Exception {
        // pi：`rawUsage.completion_tokens_details?.reasoning_tokens || 0` ⇒ 本车道 reasoning
        // **恒为数字**（与 Anthropic 车道的「不报就 null」相反）
        var usage = lastUsage(collect(stream(usageChunk(
            "{\"prompt_tokens\":10,\"completion_tokens\":50}"))));

        assertThat(usage.reasoning()).isZero();
    }

    // ═══════════════════════════════════════════════════════════
    // T7：choice.usage 回退（Moonshot 型 relay）
    // ═══════════════════════════════════════════════════════════

    @Test
    void usageInsideTheChoiceIsUsedWhenTheChunkHasNone() throws Exception {
        var usage = lastUsage(collect(stream(
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"hi\"},"
                + "\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":5,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":400}}}]}\n\n")));

        assertThat(usage.input()).isEqualTo(600);
        assertThat(usage.cacheRead()).isEqualTo(400);
    }

    // ═══════════════════════════════════════════════════════════
    // 计价
    // ═══════════════════════════════════════════════════════════

    @Test
    void costIsComputedFromTheModelPricing() throws Exception {
        var usage = lastUsage(collect(stream(usageChunk(
            "{\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":400,\"cache_write_tokens\":100}}"))));

        // 价目 input 3 / output 15 / cacheRead 0.3 / cacheWrite 3.75（$/1M）
        // ⚠️ 用容差而不是精确相等 —— 同样的算式在浮点下不保证逐位相同
        // （实测 `3.75 * 100 / 1e6` 得 1.1999999999999999E-4）。
        assertThat(usage.cost().input()).isCloseTo(3.0 * 500 / 1e6, within(1e-15));
        assertThat(usage.cost().output()).isCloseTo(15.0 * 50 / 1e6, within(1e-15));
        assertThat(usage.cost().cacheRead()).isCloseTo(0.3 * 400 / 1e6, within(1e-15));
        assertThat(usage.cost().cacheWrite()).isCloseTo(3.75 * 100 / 1e6, within(1e-15));
        assertThat(usage.cost().total()).isGreaterThan(0);
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具脚手架
    // ═══════════════════════════════════════════════════════════

    private static final ModelInfo PRICED_MODEL = new ModelInfo(
        ModelId.of("openai", "glm-5.3-flash"), "glm-5.3-flash",
        Set.of(ModelCapability.TEXT), 128_000, 16_384, false,
        new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of()));

    private static String stream(String... chunks) {
        return String.join("", chunks) + "data: [DONE]\n\n";
    }

    /** OpenAI 的 {@code include_usage} 终帧：{@code choices} 为空、{@code usage} 在顶层。 */
    private static String usageChunk(String usageJson) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"glm-5.3-flash\",\"choices\":[],\"usage\":" + usageJson + "}\n\n";
    }

    private List<StreamEvent> collect(String sseBody) throws Exception {
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
