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
 * Google 车道的 usage 归一 —— pi {@code google-generative-ai.ts:231-250} 的逐条移植
 * （包 H1 步 5，{@code docs/42 §2.1 P12/P13}，测试计划 T8）。
 *
 * <p>三条容易做错的：</p>
 * <ul>
 *   <li><b>cacheRead 是「搬移」不是「另报」</b>：{@code input = promptTokenCount −
 *       cachedContentTokenCount}（{@code :232-234}），cacheRead 单列（{@code :236}）。</li>
 *   <li><b>减法没有 {@code Math.max(0, …)} 钳位</b>（P13，裁决 D「照抄」）—— 与 OpenAI
 *       两条车道相反 ⇒ 越界的 {@code cachedContentTokenCount} 会产出<b>负</b> input。
 *       下面 {@link #negativeInputIsNotClampedAwayAsPiDoes} 专钉这一点。</li>
 *   <li><b>thoughts 双写</b>（P12，{@code :235}/{@code :238}）：既折进 output
 *       （{@code candidates + thoughts}）又单设 reasoning —— 一个量、两个去处。</li>
 * </ul>
 */
class GoogleGenerativeAiApiUsageTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // T8：搬移 + 双写 + 直取
    // ═══════════════════════════════════════════════════════════

    @Test
    void cacheReadIsMovedOutOfInputAndThoughtsFoldIntoOutputAndReasoning() throws Exception {
        var usage = lastUsage(collect(usageFrame(1000, 400, 50, 20, 1070)));

        assertThat(usage.input()).as("prompt − cached（搬移）").isEqualTo(600);
        assertThat(usage.cacheRead()).isEqualTo(400);
        assertThat(usage.output()).as("candidates + thoughts").isEqualTo(70);
        assertThat(usage.reasoning()).as("thoughts 另设 reasoning").isEqualTo(20.0);
        assertThat(usage.cacheWrite()).as("pi :237 恒 0").isZero();
        assertThat(usage.totalTokens()).as("直取 totalTokenCount").isEqualTo(1070);
    }

    @Test
    void totalTokensIsTakenFromTheProviderEvenWhenItDisagreesWithTheSum() throws Exception {
        // 四分量之和 = 600 + 70 + 400 + 0 = 1070；线格故意给 999 ⇒ 直取才等于 999。
        var usage = lastUsage(collect(usageFrame(1000, 400, 50, 20, 999)));

        assertThat(usage.totalTokens()).isEqualTo(999);
    }

    // ═══════════════════════════════════════════════════════════
    // T8 的另一半：裁决 D —— 无钳位，照抄
    // ═══════════════════════════════════════════════════════════

    @Test
    void negativeInputIsNotClampedAwayAsPiDoes() throws Exception {
        // cached（400）> prompt（100）⇒ 100 − 400 = −300。
        // pi 的减法**没有** Math.max(0, …)（google-generative-ai.ts:233-244，与 OpenAI 两条
        // 车道相反）。docs/42 裁决 D：判据是「行为和 pi 一样」，pi 没有钳位就是没有。
        // 若有人觉得「负 input 是 bug」而补上钳位 —— 这条会红，且必须回头重开裁决 D。
        var usage = lastUsage(collect(usageFrame(100, 400, 5, 0, 405)));

        assertThat(usage.input()).as("照抄 pi：无钳位，−300 就是 −300").isEqualTo(-300);
        assertThat(usage.cacheRead()).isEqualTo(400);
    }

    // ═══════════════════════════════════════════════════════════
    // 计价
    // ═══════════════════════════════════════════════════════════

    @Test
    void costIsComputedFromTheModelPricing() throws Exception {
        var usage = lastUsage(collect(usageFrame(1000, 400, 50, 20, 1070)));

        // 价目 input 3 / output 15 / cacheRead 0.3 / cacheWrite 3.75（$/1M）
        // reasoning 不减出 output（thoughts 已在 output 里）⇒ output 费按 70 计
        assertThat(usage.cost().input()).isCloseTo(3.0 * 600 / 1e6, within(1e-15));
        assertThat(usage.cost().output()).isCloseTo(15.0 * 70 / 1e6, within(1e-15));
        assertThat(usage.cost().cacheRead()).isCloseTo(0.3 * 400 / 1e6, within(1e-15));
        assertThat(usage.cost().cacheWrite()).isZero();
        assertThat(usage.cost().total()).isGreaterThan(0);
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具脚手架
    // ═══════════════════════════════════════════════════════════

    private static final ModelInfo PRICED_MODEL = new ModelInfo(
        ModelId.of("google", "gemini-2.5-flash"), "gemini-2.5-flash",
        Set.of(ModelCapability.TEXT), 128_000, 16_384, false,
        new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of()));

    /** 终帧：带全量 usageMetadata 与 {@code STOP}（保证走 done 而非「无 finish reason」报错）。 */
    private static String usageFrame(long prompt, long cached, long candidates,
                                     long thoughts, long total) {
        return "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}],"
            + "\"role\":\"model\"},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":{\"promptTokenCount\":" + prompt
            + ",\"cachedContentTokenCount\":" + cached
            + ",\"candidatesTokenCount\":" + candidates
            + ",\"thoughtsTokenCount\":" + thoughts
            + ",\"totalTokenCount\":" + total + "}}\n\n";
    }

    private List<StreamEvent> collect(String... dataLines) throws Exception {
        // ⚠️ 不补 `data: [DONE]` —— Gemini SSE 没有它，google-genai 会 JSON 解析失败
        // （GoogleGenerativeAiApiTest:196-198 的同一个坑）。
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
