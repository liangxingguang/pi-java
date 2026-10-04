package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.builtin.OpenRouterModels;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-02（原 docs/59 §4.10 与 B133 收口）：OpenRouter 路由偏好落线（pi
 * {@code openai-completions.ts:980-982}）＋ openrouter 思考形状自**内置目录**生产可达。
 *
 * <p>观测面＝真出站体（{@link RecordingHttpServer}，A-01/A-09 同款）。⚠️ 断言按键取值。</p>
 *
 * <p>三态钉子（原 docs/59 R6）：缺席**不发键**、空表发 {@code provider:{}}、有值原样
 * （含嵌套 null 存活——A-09 R11 的经树通路在此复验）。</p>
 */
class OpenRouterRoutingWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 夹具 ────────────────────────────────────────────────────

    private static ModelInfo model(ModelCompat compat, Map<String, Object> samplingParams) {
        return new ModelInfo(ModelId.of("openrouter", "openai/gpt-test"), "GPT Test",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING), 400_000, 128_000, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), samplingParams, compat);
    }

    /** 25 参规范构造的便捷包装：只有第 25 位（openRouterRouting）有值。 */
    private static ModelCompat routing(Map<String, Object> routing) {
        return new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, null, null, null, null, null, null,
            Map.of(), Map.of(), null, null, routing);
    }

    private static JsonNode body(ModelInfo model, Optional<ThinkingLevel> reasoning)
            throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "OPENROUTER_API_KEY");
            var request = new StreamRequest(model, "be brief",
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
                List.of(), -1, -1, Map.of(), reasoning);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    // ── 三态（pi :981 的 JS 真值判断）──────────────────────────────

    @Test
    void explicitRoutingBecomesTheProviderBodyKey() throws Exception {
        var table = new LinkedHashMap<String, Object>();
        table.put("order", List.of("Azure", "Together"));
        table.put("zdr", true);
        // ⚠️ 嵌套 null 必须活到线上（sort.partition?: string | null，types.ts:884）
        // —— JsonValue.from(map) 直塞会被 SDK mapper 的 NON_NULL 静默丢键（A-09 R11）。
        var sort = new LinkedHashMap<String, Object>();
        sort.put("by", "price");
        sort.put("partition", null);
        table.put("sort", sort);

        var wire = body(model(routing(table), Map.of()), Optional.empty());

        var provider = wire.path("provider");
        assertThat(provider.path("order").get(0).asText()).isEqualTo("Azure");
        assertThat(provider.path("zdr").asBoolean()).isTrue();
        assertThat(provider.path("sort").path("by").asText()).isEqualTo("price");
        assertThat(provider.path("sort").has("partition")).as("null 键存活").isTrue();
        assertThat(provider.path("sort").path("partition").isNull()).isTrue();
    }

    @Test
    void absentRoutingSendsNoProviderKey() throws Exception {
        // 探测面不给 routing（pi :1657 的 {} 无读者）⇒ 内置模型也不许凭空多这个键。
        var wire = body(model(ModelCompat.NONE, Map.of()), Optional.empty());
        assertThat(wire.has("provider")).isFalse();

        var builtin = body(OpenRouterModels.catalog()
            .find(ModelId.of("openrouter", "openai/gpt-5.1")).orElseThrow(), Optional.empty());
        assertThat(builtin.has("provider")).as("内置目录模型同样不发").isFalse();
    }

    @Test
    void emptyRoutingStillSendsAnEmptyProviderObject() throws Exception {
        // R6 的钉子：{} 在 JS 里是真值 ⇒ pi 发 provider:{}。归一成「不发」在这里必红。
        var wire = body(model(routing(Map.of()), Map.of()), Optional.empty());
        assertThat(wire.has("provider")).isTrue();
        assertThat(wire.path("provider").isObject()).isTrue();
        assertThat(wire.path("provider").isEmpty()).isTrue();
    }

    @Test
    void samplingParamsAreTheLastWriterAndWinTheKey() throws Exception {
        // pi :996-999 是「body 的最后一个变更」⇒ 同名键压过 :981 的 routing。
        var wire = body(model(routing(Map.of("zdr", true)),
                Map.of("provider", Map.of("only", List.of("x")))),
            Optional.empty());

        assertThat(wire.path("provider").path("only").get(0).asText()).isEqualTo("x");
        assertThat(wire.path("provider").has("zdr")).as("被压过，不是合并").isFalse();
    }

    // ── B133 收口：openrouter 思考形状自内置目录生产可达 ──────────────

    @Test
    void builtinCatalogReachesTheOpenRouterThinkingShape() throws Exception {
        // A-09 落的 OPENROUTER 臂（pi :931-940）此前无生产模型可命中（B133）；
        // 自内置目录取真模型：级别经 thinkingLevelMap 翻译进嵌套 reasoning 对象。
        var gpt51 = OpenRouterModels.catalog()
            .find(ModelId.of("openrouter", "openai/gpt-5.1")).orElseThrow();

        var high = body(gpt51, Optional.of(new ThinkingLevel.High()));
        assertThat(high.path("reasoning").path("effort").asText()).isEqualTo("high");
        // ⚠️ 本臂**不写**顶层 reasoning_effort（pi :934-940 只写嵌套对象）。
        assertThat(high.has("reasoning_effort")).isFalse();

        // off（Optional.empty()）且 tlm off:"none" 显式支持 ⇒ 关闭也走嵌套形状（pi :938-940）。
        var off = body(gpt51, Optional.empty());
        assertThat(off.path("reasoning").path("effort").asText()).isEqualTo("none");
    }
}
