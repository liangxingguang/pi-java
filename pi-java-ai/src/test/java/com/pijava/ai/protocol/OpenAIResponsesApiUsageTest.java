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
 * OpenAI-responses 车道的 usage 归一 —— pi {@code openai-responses-shared.ts:559-582}
 * {@code finalizeResponse} 的逐条移植（包 H1 步 5，{@code docs/42 §2.1 P11}）。
 *
 * <p>三条容易做错的：</p>
 * <ul>
 *   <li><b>减法</b>（{@code :571}）：OpenAI 把 cached 与 cache-write <b>都含在</b>
 *       {@code input_tokens} 里 ⇒ 两个都要减（注释逐字点名），且有 {@code Math.max(0, …)}
 *       钳位（与 completions 同、与 Google 反）。</li>
 *   <li><b>{@code totalTokens} 直取 provider 的 {@code total_tokens}</b>（P3 的分派：
 *       responses/Google 直取，Anthropic/completions 自算）⇒ 下面有一条刻意把线格
 *       total 与四分量之和<b>造成不等</b>来钉住学派。</li>
 *   <li><b>{@code reasoning} 用 {@code || 0}</b>（{@code :575}）⇒ 本车道恒为数字，
 *       与 completions 同向、与 Anthropic 的「不报就 null」反向。</li>
 * </ul>
 */
class OpenAIResponsesApiUsageTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 减法 + 钳位（pi :571）
    // ═══════════════════════════════════════════════════════════

    @Test
    void cachedAndCacheWriteTokensAreBothSubtractedFromInputTokens() throws Exception {
        var usage = lastUsage(collect(usageSse(
            "{\"input_tokens\":1000,\"output_tokens\":50,\"total_tokens\":600,"
                + "\"input_tokens_details\":{\"cached_tokens\":400,\"cache_write_tokens\":100},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":0}}")));

        assertThat(usage.input()).as("input − cached − cacheWrite").isEqualTo(500);
        assertThat(usage.cacheRead()).isEqualTo(400);
        assertThat(usage.cacheWrite()).isEqualTo(100);
        assertThat(usage.output()).isEqualTo(50);
    }

    @Test
    void inputIsClampedAtZeroWhenDetailsExceedInputTokens() throws Exception {
        // 80 + 50 > 100 ⇒ Math.max(0, …) 生效（本车道有钳位，Google 没有）
        var usage = lastUsage(collect(usageSse(
            "{\"input_tokens\":100,\"output_tokens\":5,\"total_tokens\":200,"
                + "\"input_tokens_details\":{\"cached_tokens\":80,\"cache_write_tokens\":50},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":0}}")));

        assertThat(usage.input()).as("Math.max(0, …) 钳位").isZero();
        assertThat(usage.cacheRead()).isEqualTo(80);
        assertThat(usage.cacheWrite()).isEqualTo(50);
    }

    // ═══════════════════════════════════════════════════════════
    // totalTokens 直取 provider（P3 学派）
    // ═══════════════════════════════════════════════════════════

    @Test
    void totalTokensIsTakenFromTheProviderEvenWhenItDisagreesWithTheSum() throws Exception {
        // 四分量之和 = 500 + 50 + 400 + 100 = 1050，线格给 999 —— 直取才等于 999。
        // completions 车道是反的（自算，不用 provider 值），两条各自照 pi。
        var usage = lastUsage(collect(usageSse(
            "{\"input_tokens\":1000,\"output_tokens\":50,\"total_tokens\":999,"
                + "\"input_tokens_details\":{\"cached_tokens\":400,\"cache_write_tokens\":100},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":0}}")));

        assertThat(usage.totalTokens()).as("直取 total_tokens，不自算").isEqualTo(999);
    }

    // ═══════════════════════════════════════════════════════════
    // reasoning：本车道恒为数字（pi 用 `|| 0`）
    // ═══════════════════════════════════════════════════════════

    @Test
    void reasoningTokensComeFromOutputTokensDetails() throws Exception {
        var usage = lastUsage(collect(usageSse(
            "{\"input_tokens\":10,\"output_tokens\":50,\"total_tokens\":60,"
                + "\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":33}}")));

        assertThat(usage.reasoning()).isEqualTo(33.0);
    }

    @Test
    void reasoningIsZeroRatherThanAbsentWhenDetailsReportNone() throws Exception {
        var usage = lastUsage(collect(usageSse(
            "{\"input_tokens\":10,\"output_tokens\":50,\"total_tokens\":60,"
                + "\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":0}}")));

        assertThat(usage.reasoning()).as("恒为数字（与 Anthropic 的 null 反向）").isZero();
    }

    // ═══════════════════════════════════════════════════════════
    // 计价（calculateCost 在 `if (response?.usage)` 块内取值之后、P11）
    // ═══════════════════════════════════════════════════════════

    @Test
    void costIsComputedFromTheModelPricing() throws Exception {
        var usage = lastUsage(collect(usageSse(
            "{\"input_tokens\":1000,\"output_tokens\":50,\"total_tokens\":600,"
                + "\"input_tokens_details\":{\"cached_tokens\":400,\"cache_write_tokens\":100},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":0}}")));

        // 价目 input 3 / output 15 / cacheRead 0.3 / cacheWrite 3.75（$/1M）
        // ⚠️ 容差而不是逐位相等（同 OpenAICompletionsApiUsageTest 的理由）
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
        ModelId.of("openai", "gpt-4o"), "gpt-4o",
        Set.of(ModelCapability.TEXT), 128_000, 16_384, false,
        new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of()));

    /** created(in_progress) + completed(带 usage) 的最小两条流。 */
    private static String usageSse(String usageJson) {
        return "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"in_progress\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null}}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"completed\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":" + usageJson + "}}\n\n"
            + "data: [DONE]\n\n";
    }

    private List<StreamEvent> collect(String sseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        var api = new OpenAIResponsesApi(new ApiOptions(
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
