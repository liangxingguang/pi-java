package com.pijava.coding.agent.rpc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.catalog.InputLimits;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.PromptCacheLifetimes;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 20（B31，T2a）：get_state.model 全对象线格——pi {@code Model} schema
 * （model-schema.ts:3-96／types.ts:819-857）逐键对拍。
 */
class RpcModelWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ProviderConfig CONFIG = new ProviderConfig(
        "testprov", "Test Provider", "https://api.testprov.example/v1",
        null, Protocol.OPENAI_COMPLETIONS,
        Set.of(Protocol.OPENAI_COMPLETIONS), ModelCatalog.empty());

    private static ModelInfo fullModel() {
        var tlm = new LinkedHashMap<ModelThinkingLevel, Optional<String>>();
        // pi 三态：字符串映射／显式 null（不支持）／键缺席（该级不出线）。
        tlm.put(ModelThinkingLevel.of(new ThinkingLevel.High()), Optional.of("high-effort"));
        tlm.put(ModelThinkingLevel.of(new ThinkingLevel.Low()), Optional.empty());
        var sptl = new LinkedHashMap<ModelThinkingLevel, Map<String, Object>>();
        sptl.put(ModelThinkingLevel.of(new ThinkingLevel.High()), Map.of("temperature", 0.2));
        return new ModelInfo(
            ModelId.of("testprov", "tp-1"),
            "TP One",
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT,
                ModelCapability.THINKING),
            200_000,
            8_192,
            false,
            new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of(
                new PricingInfo.CostTier(100_000, -1, 20.0, 0.5, 5.0))),
            new ThinkingLevelMap(tlm),
            Map.of("X-Model-Header", "v1"),
            Map.of("top_p", 0.9),
            ModelCompat.NONE,
            null,                       // api 缺席 ⇒ provider 默认协议
            null,                       // baseUrl 缺席 ⇒ provider 默认
            new PromptCacheLifetimes(300, 3600),
            new InputLimits(1_000_000, new InputLimits.ImageLimits(
                new InputLimits.ResizeOptions(1568, 1568, 5_000_000, 80), 4, 20)),
            sptl);
    }

    @Test
    void fullMappingMatchesPiSchemaKeys() throws Exception {
        var wire = JSON.writeValueAsString(RpcModel.of(fullModel(), CONFIG, JSON));
        var node = JSON.readTree(wire);

        assertThat(node.get("id").asText()).isEqualTo("tp-1");
        assertThat(node.get("name").asText()).isEqualTo("TP One");
        // api/baseUrl 生效值＝provider 默认（pi 目录恒有值对位）。
        assertThat(node.get("api").asText()).isEqualTo("openai-completions");
        assertThat(node.get("provider").asText()).isEqualTo("testprov");
        assertThat(node.get("baseUrl").asText()).isEqualTo("https://api.testprov.example/v1");
        assertThat(node.get("input")).hasSize(2);
        assertThat(node.get("input").get(0).asText()).isEqualTo("text");
        assertThat(node.get("input").get(1).asText()).isEqualTo("image");
        assertThat(node.get("reasoning").asBoolean()).isTrue();
        assertThat(node.get("contextWindow").asInt()).isEqualTo(200_000);
        assertThat(node.get("maxTokens").asInt()).isEqualTo(8_192);
        // chat 模型的 type 恒省略（pi 同）。
        assertThat(node.has("type")).isFalse();
    }

    @Test
    void costMapsUnknownMinusOneToPiCatalogZero() throws Exception {
        var wire = JSON.readTree(
            JSON.writeValueAsString(RpcModel.of(fullModel(), CONFIG, JSON)));
        var cost = wire.get("cost");
        assertThat(cost.get("input").asDouble()).isEqualTo(3.0);
        assertThat(cost.get("output").asDouble()).isEqualTo(15.0);
        assertThat(cost.get("cacheRead").asDouble()).isEqualTo(0.3);
        assertThat(cost.get("cacheWrite").asDouble()).isEqualTo(3.75);
        var tier = cost.get("tiers").get(0);
        assertThat(tier.get("inputTokensAbove").asDouble()).isEqualTo(100_000.0);
        // 裁决④：tier 内 −1 同回落 0（pi 目录生成语义）。
        assertThat(tier.get("input").asDouble()).isEqualTo(0.0);
        assertThat(tier.get("output").asDouble()).isEqualTo(20.0);
    }

    @Test
    void unknownPricingEmitsZerosAndOmitsTiers() throws Exception {
        var info = new ModelInfo(
            ModelId.of("testprov", "tp-x"), "TP X", Set.of(ModelCapability.TEXT),
            0, 0, false, PricingInfo.UNKNOWN,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(), Map.of(), Map.of(),
            ModelCompat.NONE);
        var wire = JSON.readTree(
            JSON.writeValueAsString(RpcModel.of(info, CONFIG, JSON)));
        assertThat(wire.get("cost").get("input").asDouble()).isEqualTo(0.0);
        assertThat(wire.get("cost").has("tiers")).isFalse();
        assertThat(wire.get("reasoning").asBoolean()).isFalse();
        assertThat(wire.get("input")).hasSize(1);
    }

    @Test
    void thinkingLevelMapKeepsExplicitNullEntriesOnWire() throws Exception {
        var wire = JSON.readTree(
            JSON.writeValueAsString(RpcModel.of(fullModel(), CONFIG, JSON)));
        var tlm = wire.get("thinkingLevelMap");
        // 字符串映射＋显式 null（pi Partial<Record<level, string|null>> 保真）。
        assertThat(tlm.get("high").asText()).isEqualTo("high-effort");
        assertThat(tlm.has("low")).isTrue();
        assertThat(tlm.get("low").isNull()).isTrue();
        assertThat(tlm.has("minimal")).isFalse();
    }

    @Test
    void promptCacheInputLimitsHeadersAndSamplingWireThrough() throws Exception {
        var wire = JSON.readTree(
            JSON.writeValueAsString(RpcModel.of(fullModel(), CONFIG, JSON)));
        assertThat(wire.get("promptCache").get("short").asInt()).isEqualTo(300);
        assertThat(wire.get("promptCache").get("long").asInt()).isEqualTo(3600);
        var limits = wire.get("inputLimits");
        assertThat(limits.get("maxRequestBytes").asInt()).isEqualTo(1_000_000);
        assertThat(limits.get("images").get("resize").get("jpegQuality").asInt()).isEqualTo(80);
        assertThat(limits.get("images").get("maxPerMessage").asInt()).isEqualTo(4);
        assertThat(limits.get("images").get("maxPerRequest").asInt()).isEqualTo(20);
        assertThat(wire.get("headers").get("X-Model-Header").asText()).isEqualTo("v1");
        assertThat(wire.get("samplingParams").get("top_p").asDouble()).isEqualTo(0.9);
        assertThat(wire.get("samplingParamsByThinkingLevel").get("high")
            .get("temperature").asDouble()).isEqualTo(0.2);
    }

    @Test
    void compatNoneIsOmittedFromWire() throws Exception {
        var wire = JSON.readTree(
            JSON.writeValueAsString(RpcModel.of(fullModel(), CONFIG, JSON)));
        assertThat(wire.has("compat")).isFalse();
    }

    @Test
    void explicitApiAndBaseUrlWinOverProviderDefaults() throws Exception {
        var info = new ModelInfo(
            ModelId.of("testprov", "tp-2"), "TP Two", Set.of(ModelCapability.TEXT),
            1, 1, false, PricingInfo.UNKNOWN,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(), Map.of(), Map.of(),
            ModelCompat.NONE, "anthropic-messages", "https://relay.example/v1");
        var wire = JSON.readTree(
            JSON.writeValueAsString(RpcModel.of(info, CONFIG, JSON)));
        assertThat(wire.get("api").asText()).isEqualTo("anthropic-messages");
        assertThat(wire.get("baseUrl").asText()).isEqualTo("https://relay.example/v1");
    }
}
