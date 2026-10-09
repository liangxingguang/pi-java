package com.pijava.ai.provider.builtin;

import java.util.List;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 44：xai 内置目录（4 条 chat 模型）—— 数据转录、compat 规则、api 派发标记。
 *
 * <p>转录源 pi {@code providers/data/xai.json}（锚点 {@code 200387122}，快照 2026-10-04）。
 * ⚠️ 该 JSON 的 <b>map 键</b>是 {@code "chat:grok-4.3"}，而条目内的 {@code "id"} 字段才是真 id
 * （{@code "grok-4.3"}）—— 目录用后者，夹具钉的是后者。</p>
 */
class XaiModelsTest {

    private static ModelInfo model(String id) {
        return XaiModels.catalog().find(ModelId.of("xai", id)).orElseThrow();
    }

    private static ModelThinkingLevel level(String name) {
        return ModelThinkingLevel.parse(name).orElseThrow();
    }

    @Test
    void catalogHoldsExactlyTheFourPiChatModels() {
        // ⚠️ BuiltinCatalog 底层是 HashMap ⇒ listModels() 顺序不保证，必须用无序断言。
        var ids = XaiModels.catalog().listModels().stream()
            .map(m -> m.id().modelName())
            .toList();
        assertThat(ids).containsExactlyInAnyOrder("grok-4.3", "grok-4.5", "grok-4.6", "grok-4.7");
        assertThat(ids).doesNotHaveDuplicates();
        // 全部条目挂在 xai 名下（不是别的 provider）。
        assertThat(XaiModels.catalog().listModels())
            .allSatisfy(m -> assertThat(m.id().provider()).isEqualTo("xai"));
    }

    @Test
    void everyEntryRidesTheResponsesLaneVerbatim() {
        // pi 数据每条 api 都是 "openai-responses"；缺席会退回 provider 默认协议 —— 那恰好
        // 也是 responses，但显式写入才是与 pi 同形（M2 探针钉的就是这一位）。
        assertThat(XaiModels.catalog().listModels())
            .allSatisfy(m -> assertThat(m.api()).isEqualTo("openai-responses"));
    }

    @Test
    void baseUrlStaysAbsentSoTheProviderDefaultApplies() {
        // pi 数据每条都写 baseUrl，但与 provider 默认同源 ⇒ 不逐条转录（与 OpenRouterModels 同口径）。
        assertThat(XaiModels.catalog().listModels())
            .allSatisfy(m -> assertThat(m.baseUrl()).isNull());
    }

    @Test
    void longCacheRetentionIsOffOnEveryEntry() {
        // pi XAI_RESPONSES_COMPAT = { supportsLongCacheRetention: false }，应用点**无条件**
        // （generate-models.ts:2053，与 id 无关）⇒ 四条恒 false。
        assertThat(XaiModels.catalog().listModels())
            .allSatisfy(m -> assertThat(m.compat().supportsLongCacheRetention()).isFalse());
        // 其余 responses 车道字段照 pi 缺省（缺席 ⇒ 解析层补），不被该规则顺手污染。
        var grok = model("grok-4.7");
        assertThat(grok.compat().supportsStrictMode()).isNull();
        assertThat(grok.compat().supportsDeveloperRole()).isNull();
    }

    @Test
    void transcribedLimitsAndCostsMatchThePiSnapshot() {
        var v43 = model("grok-4.3");
        assertThat(v43.displayName()).isEqualTo("Grok 4.3");
        assertThat(v43.maxInputTokens()).isEqualTo(1_000_000);
        assertThat(v43.maxOutputTokens()).isEqualTo(30_000);
        assertThat(v43.pricing().inputPrice()).isEqualTo(1.25);
        assertThat(v43.pricing().outputPrice()).isEqualTo(2.5);
        assertThat(v43.pricing().cacheReadPrice()).isEqualTo(0.2);
        assertThat(v43.pricing().cacheWritePrice()).isEqualTo(0.0);
        // >200k 档（inputTokensAbove:200000）逐字。
        assertThat(v43.pricing().tiers()).singleElement().satisfies(t -> {
            assertThat(t.inputTokensAbove()).isEqualTo(200_000);
            assertThat(t.inputPrice()).isEqualTo(2.5);
            assertThat(t.outputPrice()).isEqualTo(5.0);
            assertThat(t.cacheReadPrice()).isEqualTo(0.4);
            assertThat(t.cacheWritePrice()).isEqualTo(0.0);
        });

        // 同族三条的窗口与价目相同，唯 cacheRead 分两档（4.5=0.3 / 4.6·4.7=0.5）。
        for (var id : List.of("grok-4.5", "grok-4.6", "grok-4.7")) {
            var m = model(id);
            assertThat(m.maxInputTokens()).isEqualTo(500_000);
            assertThat(m.maxOutputTokens()).isEqualTo(500_000);
            assertThat(m.pricing().inputPrice()).isEqualTo(2.0);
            assertThat(m.pricing().outputPrice()).isEqualTo(6.0);
        }
        assertThat(model("grok-4.5").pricing().cacheReadPrice()).isEqualTo(0.3);
        assertThat(model("grok-4.6").pricing().cacheReadPrice()).isEqualTo(0.5);
        assertThat(model("grok-4.7").pricing().cacheReadPrice()).isEqualTo(0.5);
    }

    @Test
    void capabilitiesFollowThePiDataKeys() {
        // pi 四条恒 reasoning:true、input:["text","image"]、cacheRead>0。
        assertThat(XaiModels.catalog().listModels()).allSatisfy(m ->
            assertThat(m.capabilities()).containsExactlyInAnyOrder(
                ModelCapability.TEXT, ModelCapability.IMAGE_INPUT, ModelCapability.TOOL_USE,
                ModelCapability.THINKING, ModelCapability.STREAMING,
                ModelCapability.PROMPT_CACHING));
    }

    @Test
    void thinkingLevelMapIsTranscribedPerModel() {
        // grok-4.3：off 有映射（"none"）、xhigh/max 显式不支持。
        var v43 = model("grok-4.3").thinkingLevelMap();
        assertThat(v43.mapped(level("off"))).contains("none");
        assertThat(v43.mapped(level("low"))).contains("low");
        assertThat(v43.explicitlyUnsupported(level("xhigh"))).isTrue();
        assertThat(v43.explicitlyUnsupported(level("max"))).isTrue();

        // grok-4.5：off 显式不支持、xhigh 不支持。
        var v45 = model("grok-4.5").thinkingLevelMap();
        assertThat(v45.explicitlyUnsupported(level("off"))).isTrue();
        assertThat(v45.explicitlyUnsupported(level("xhigh"))).isTrue();

        // grok-4.6 / grok-4.7：off 不支持，但 xhigh **有**映射（"xhigh"）、max 仍不支持。
        for (var id : List.of("grok-4.6", "grok-4.7")) {
            var tlm = model(id).thinkingLevelMap();
            assertThat(tlm.explicitlyUnsupported(level("off"))).isTrue();
            assertThat(tlm.mapped(level("xhigh"))).contains("xhigh");
            assertThat(tlm.explicitlyUnsupported(level("max"))).isTrue();
        }
    }

    @Test
    void registeredInProviderCatalogWithItsApiKeyEnvVar() {
        var provider = ProviderCatalog.all().stream()
            .filter(p -> p.name().equals("xai"))
            .findFirst()
            .orElseThrow();
        var config = ((com.pijava.ai.provider.ConfigurableProvider) provider).providerConfig();
        assertThat(config.displayName()).isEqualTo("xAI");
        assertThat(config.defaultBaseUrl()).isEqualTo("https://api.x.ai/v1");
        assertThat(config.apiKeyEnvVar()).isEqualTo("XAI_API_KEY");
        assertThat(provider.supportedProtocols()).containsExactly(
            com.pijava.ai.provider.Protocol.OPENAI_RESPONSES);
        // 目录经 ProviderCatalog 聚合后可按 xai/… 查到。
        assertThat(ProviderCatalog.allModels().find(ModelId.of("xai", "grok-4.7"))).isPresent();
    }

    @Test
    void everyCataloguedEntryIsReachableFromTheAggregate() {
        var aggregate = ProviderCatalog.allModels();
        assertThat(XaiModels.catalog().listModels())
            .allSatisfy(m -> assertThat(aggregate.find(m.id())).isPresent());
        assertThat(XaiModels.catalog().listModels()).hasSize(4);
    }
}
