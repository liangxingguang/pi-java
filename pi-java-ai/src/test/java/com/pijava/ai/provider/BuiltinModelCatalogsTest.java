package com.pijava.ai.provider;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.Usage;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.CostCalculator;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 批 3a T1：npm {@code @earendil-works/pi-ai@1.1.0} 生成分片装载器
 * （{@link BuiltinModelCatalogs}）—— 对齐 pi {@code flattenChatModelCatalog}
 * （model-catalog.ts:41-63）：588 chat、文件序、身份校验、字段值域。
 */
class BuiltinModelCatalogsTest {

    private static ModelInfo model(String provider, String id) {
        return BuiltinModelCatalogs.catalog(provider)
            .find(ModelId.of(provider, id)).orElseThrow();
    }

    @Test
    void chatCountsPerShard() {
        // node 对 1.1.0 分片逐文件统计（只收 type:"chat"）。
        Map<String, Integer> expected = Map.ofEntries(
            Map.entry("anthropic", 17),
            Map.entry("openai", 44),
            Map.entry("google", 22),
            Map.entry("deepseek", 2),
            Map.entry("mistral", 40),
            Map.entry("moonshotai", 4),
            Map.entry("moonshotai-cn", 4),
            Map.entry("zai", 7),
            Map.entry("zai-coding-cn", 4),
            Map.entry("qwen-token-plan-cn", 20),
            Map.entry("xiaomi", 6),
            Map.entry("xiaomi-token-plan-cn", 4),
            Map.entry("minimax", 3),
            Map.entry("minimax-cn", 3),
            Map.entry("ant-ling", 3),
            Map.entry("openrouter", 401),
            Map.entry("xai", 4));
        int total = 0;
        for (var e : expected.entrySet()) {
            int actual = BuiltinModelCatalogs.catalog(e.getKey()).listModels().size();
            assertThat(actual).as("%s chat count", e.getKey()).isEqualTo(e.getValue());
            total += actual;
        }
        assertThat(total).as("17 shards carry 588 chat models").isEqualTo(588);
    }

    @Test
    void aggregateCatalogAddsImagesAndOllama() {
        // 588 chat + 8 OpenRouter image models + 2 ollama = 598（provider 数 19 不变）。
        assertThat(ProviderCatalog.allModels().listModels()).hasSize(598);
    }

    @Test
    void fileOrderIsPreservedByTheFlatten() {
        var anthropic = BuiltinModelCatalogs.catalog("anthropic").listModels();
        assertThat(anthropic.getFirst().id().modelName()).isEqualTo("claude-fable-5");
        var openrouter = BuiltinModelCatalogs.catalog("openrouter").listModels();
        // 组序 anthropic-messages 在 openai-completions 前。
        assertThat(openrouter.getFirst().id().modelName()).isEqualTo("anthropic/claude-fable-5");
    }

    @Test
    void fableFiveCarriesEveryGeneratedField() {
        var fable = model("anthropic", "claude-fable-5");

        assertThat(fable.api()).isEqualTo("anthropic-messages");
        assertThat(fable.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(fable.capabilities()).contains(
            ModelCapability.TEXT, ModelCapability.TOOL_USE, ModelCapability.STREAMING,
            ModelCapability.THINKING, ModelCapability.IMAGE_INPUT);
        // pi 无 PROMPT_CACHING/COMPUTER_USE 布尔：生成数据不派生这两个 Java 方言位。
        assertThat(fable.capabilities())
            .doesNotContain(ModelCapability.PROMPT_CACHING, ModelCapability.COMPUTER_USE);
        assertThat(fable.maxInputTokens()).isEqualTo(1_000_000);
        assertThat(fable.maxOutputTokens()).isEqualTo(128_000);

        var p = fable.pricing();
        assertThat(p.inputPrice()).isEqualTo(10);
        assertThat(p.outputPrice()).isEqualTo(50);
        assertThat(p.cacheReadPrice()).isEqualTo(1);
        assertThat(p.cacheWritePrice()).isEqualTo(12.5);

        var compat = fable.compat();
        assertThat(compat.forceAdaptiveThinking()).isTrue();
        assertThat(compat.supportsStrictTools()).isTrue();
        assertThat(compat.supportsMidConvoSystemMessages()).isTrue();
        assertThat(compat.supportsMidConvoToolChanges()).isTrue();

        var fallbacks = compat.allowedFallbackModels();
        assertThat(fallbacks).hasSize(2);
        assertThat(fallbacks.get(0).provider()).isEqualTo("anthropic");
        assertThat(fallbacks.get(0).model()).isEqualTo("claude-opus-4-8");
        assertThat(fallbacks.get(0).cost().inputPrice()).isEqualTo(5);
        assertThat(fallbacks.get(0).cost().outputPrice()).isEqualTo(25);
        assertThat(fallbacks.get(0).cost().cacheReadPrice()).isEqualTo(0.5);
        assertThat(fallbacks.get(0).cost().cacheWritePrice()).isEqualTo(6.25);
        assertThat(fallbacks.get(1).model()).isEqualTo("claude-opus-5");

        var tlm = fable.thinkingLevelMap();
        assertThat(tlm.explicitlyUnsupported(ModelThinkingLevel.parse("off").orElseThrow()))
            .isTrue();
        assertThat(tlm.mapped(ModelThinkingLevel.parse("xhigh").orElseThrow()))
            .contains("xhigh");
        assertThat(tlm.mapped(ModelThinkingLevel.parse("max").orElseThrow()))
            .contains("max");

        // 批 3a（#86/#90）：promptCache 两档寿命、inputLimits 三层值。
        assertThat(fable.promptCache().shortSeconds()).isEqualTo(300);
        assertThat(fable.promptCache().longSeconds()).isEqualTo(3600);
        assertThat(fable.inputLimits().maxRequestBytes()).isEqualTo(33_554_432);
        assertThat(fable.inputLimits().images().maxPerRequest()).isEqualTo(600);
        assertThat(fable.inputLimits().images().maxPerMessage()).isNull();
        assertThat(fable.inputLimits().images().resize().maxWidth()).isEqualTo(2000);
        assertThat(fable.inputLimits().images().resize().maxHeight()).isEqualTo(2000);
        assertThat(fable.inputLimits().images().resize().maxBytes()).isEqualTo(4_718_592);
        assertThat(fable.inputLimits().images().resize().jpegQuality()).isEqualTo(80);
        // 生成目录对 sptl 零生产者（#91 只来自 models.json）。
        assertThat(fable.samplingParamsByThinkingLevel()).isEmpty();
        assertThat(fable.samplingParams()).isEmpty();
    }

    @Test
    void haikuFiveFiveTierAppliesToTheWholeRequestAtStrictThreshold() {
        var haiku = model("anthropic", "claude-haiku-5-5");
        // 基价 0.1/0.5（B232 漂移钉）。
        assertThat(haiku.pricing().inputPrice()).isEqualTo(0.1);
        assertThat(haiku.pricing().outputPrice()).isEqualTo(0.5);
        assertThat(haiku.pricing().tiers()).hasSize(1);
        var tier = haiku.pricing().tiers().getFirst();
        assertThat(tier.inputTokensAbove()).isEqualTo(100_000);
        assertThat(tier.inputPrice()).isEqualTo(0.5);
        assertThat(tier.outputPrice()).isEqualTo(2.5);
        assertThat(tier.cacheReadPrice()).isEqualTo(0.05);
        assertThat(tier.cacheWritePrice()).isEqualTo(0.625);

        // 严格 >：恰在阈值上仍走基价。
        var atThreshold = CostCalculator.calculateCost(haiku.pricing(), Usage.of(100_000, 0));
        assertThat(atThreshold.input()).isCloseTo(0.01, within(1e-12));
        var aboveThreshold = CostCalculator.calculateCost(haiku.pricing(), Usage.of(100_001, 0));
        // 档价适用于整请求（不是超额部分）。
        assertThat(aboveThreshold.input()).isCloseTo(0.050_000_5, within(1e-9));
    }

    @Test
    void kimiK3IsPinnedIdenticallyOnBothMoonshotProviders() {
        // B232（a2eef9eb6）：kimi-k3 钉价 {3,15,0.3,0}，两家同值。
        for (var provider : List.of("moonshotai", "moonshotai-cn")) {
            var kimi = model(provider, "kimi-k3").pricing();
            assertThat(kimi.inputPrice()).as("%s input", provider).isEqualTo(3);
            assertThat(kimi.outputPrice()).as("%s output", provider).isEqualTo(15);
            assertThat(kimi.cacheReadPrice()).as("%s cacheRead", provider).isEqualTo(0.3);
            assertThat(kimi.cacheWritePrice()).as("%s cacheWrite", provider).isZero();
        }
    }

    @Test
    void openRouterKeepsTwoLanesBatchIdsAndTieredPricing() {
        var or = BuiltinModelCatalogs.catalog("openrouter").listModels();
        var anthropicLane = or.stream().filter(m -> "anthropic-messages".equals(m.api())).toList();
        var completionsLane = or.stream().filter(m -> "openai-completions".equals(m.api())).toList();
        assertThat(anthropicLane).hasSize(16);
        assertThat(completionsLane).hasSize(385);
        assertThat(anthropicLane).allSatisfy(m -> {
            assertThat(m.api()).isEqualTo("anthropic-messages");
            assertThat(m.baseUrl()).isEqualTo("https://openrouter.ai/api");
        });
        assertThat(completionsLane).allSatisfy(m ->
            assertThat(m.baseUrl()).isEqualTo("https://openrouter.ai/api/v1"));

        // :batch id 自带冒号，find 的键是 provider/id，不能被 pattern 解析切碎。
        assertThat(BuiltinModelCatalogs.catalog("openrouter")
            .find(ModelId.of("openrouter", "anthropic/claude-fable-5:batch"))).isPresent();

        // 两档定价（32K/128K）经 CostCalculator 选档（阈值计 input+cacheRead+cacheWrite）。
        var qwen = model("openrouter", "qwen/qwen3-coder-flash").pricing();
        assertThat(qwen.inputPrice()).isEqualTo(0.195);
        assertThat(qwen.tiers()).hasSize(2);
        assertThat(CostCalculator.calculateCost(qwen, Usage.of(32_000, 0)).input())
            .isCloseTo(0.195 / 1_000_000 * 32_000, within(1e-12));
        assertThat(CostCalculator.calculateCost(qwen, Usage.of(32_001, 0)).input())
            .isCloseTo(0.325 / 1_000_000 * 32_001, within(1e-9));
        assertThat(CostCalculator.calculateCost(qwen, Usage.of(128_001, 0)).input())
            .isCloseTo(0.52 / 1_000_000 * 128_001, within(1e-9));
    }

    @Test
    void imageClassifierAndEmbeddingEntriesAreNotCarried() {
        // 61 image 与 15 classifier 出分母（#33/#94 R5）；两条 Java 独有的 embedding 不载（B97）。
        var openrouter = BuiltinModelCatalogs.catalog("openrouter");
        assertThat(openrouter.find(ModelId.of("openrouter", "black-forest-labs/flux.2-flex")))
            .as("image-generation entries (openrouter-images group) are not carried").isEmpty();
        // openai 分片含 45 条目（44 chat ＋ 1 openai-decisions classifier）；chat 计数 44
        // 已在 chatCountsPerShard 钉住 classifier 未载（classifier 与某 chat 模型同 id，
        // 故这里不能按 id 探针）。
        assertThat(BuiltinModelCatalogs.catalog("openai")
            .find(ModelId.of("openai", "text-embedding-3-small")))
            .as("Java-only embedding ids are gone (B97)").isEmpty();
        assertThat(BuiltinModelCatalogs.catalog("openai")
            .find(ModelId.of("openai", "text-embedding-3-large"))).isEmpty();
    }

    @Test
    void everyEntryHoldsAKnownApiAndItsFourRates() {
        // api 闭集在装载期已逐条过 Protocol.fromWire（modelApiOf 响亮抛）；这里再钉值域。
        var apis = BuiltinModelCatalogs.shardIds().stream()
            .flatMap(s -> BuiltinModelCatalogs.catalog(s).listModels().stream())
            .map(ModelInfo::api)
            .collect(java.util.stream.Collectors.toSet());
        assertThat(apis).containsExactlyInAnyOrder(
            "anthropic-messages", "openai-responses", "openai-completions",
            "google-generative-ai", "mistral-conversations");
    }

    @Test
    void identityMismatchesFailLoudly() throws Exception {
        var mapper = new ObjectMapper();
        var good = (ObjectNode) mapper.readTree("""
            {"openai-completions": {"chat:good": {"type":"chat","id":"good",
              "api":"openai-completions","provider":"p","name":"Good","reasoning":false,
              "input":["text"],"cost":{"input":1,"output":2,"cacheRead":0,"cacheWrite":0},
              "contextWindow":1000,"maxTokens":100,"baseUrl":"https://x"}}}
            """);
        assertThat(BuiltinModelCatalogs.loadChatModels("p", good)).hasSize(1);

        var badKey = (ObjectNode) mapper.readTree("""
            {"openai-completions": {"chat:wrong": {"type":"chat","id":"good",
              "api":"openai-completions","provider":"p","name":"Good","reasoning":false,
              "input":["text"],"cost":{"input":1,"output":2,"cacheRead":0,"cacheWrite":0},
              "contextWindow":1000,"maxTokens":100,"baseUrl":"https://x"}}}
            """);
        assertThatThrownBy(() -> BuiltinModelCatalogs.loadChatModels("p", badKey))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not match chat:good");

        var badProvider = (ObjectNode) mapper.readTree("""
            {"openai-completions": {"chat:good": {"type":"chat","id":"good",
              "api":"openai-completions","provider":"other","name":"Good","reasoning":false,
              "input":["text"],"cost":{"input":1,"output":2,"cacheRead":0,"cacheWrite":0},
              "contextWindow":1000,"maxTokens":100,"baseUrl":"https://x"}}}
            """);
        assertThatThrownBy(() -> BuiltinModelCatalogs.loadChatModels("p", badProvider))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("provider field");

        var badApi = (ObjectNode) mapper.readTree("""
            {"openai-completions": {"chat:good": {"type":"chat","id":"good",
              "api":"anthropic-messages","provider":"p","name":"Good","reasoning":false,
              "input":["text"],"cost":{"input":1,"output":2,"cacheRead":0,"cacheWrite":0},
              "contextWindow":1000,"maxTokens":100,"baseUrl":"https://x"}}}
            """);
        assertThatThrownBy(() -> BuiltinModelCatalogs.loadChatModels("p", badApi))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not match group key");
    }

    @Test
    void unknownShardAndMissingResourceFailLoudly() {
        assertThatThrownBy(() -> BuiltinModelCatalogs.catalog("amazon-bedrock"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a builtin catalog provider");
        // 未列名 id 的模型不造：只有 17 家可装载。
        assertThat(BuiltinModelCatalogs.shardIds())
            .doesNotContain("amazon-bedrock", "azure", "vercel-ai-gateway");
    }

    @Test
    void catalogsAreCached() {
        assertThat(BuiltinModelCatalogs.catalog("deepseek"))
            .isSameAs(BuiltinModelCatalogs.catalog("deepseek"));
    }

    @Test
    void pricingIsNeverZeroedOrUnknownForGeneratedEntries() {
        // 资源数据每条 chat 四费率齐全（装载期已校验）；抽查若干家不为 -1。
        for (var ref : List.of(
                Map.entry("google", "gemini-2.5-pro"),
                Map.entry("mistral", "mistral-large-latest"),
                Map.entry("xai", "grok-4.7"))) {
            var p = model(ref.getKey(), ref.getValue()).pricing();
            assertThat(p.inputPrice()).as("%s/%s", ref.getKey(), ref.getValue()).isPositive();
            assertThat(p.outputPrice()).isPositive();
        }
    }
}
