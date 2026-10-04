package com.pijava.ai.provider.builtin;

import java.util.List;
import java.util.Set;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ThinkingFormat;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-02（原 docs/59 §4.3）：OpenRouter 内置目录 —— 数据转录、谓词 compat、api 派发标记。
 */
class OpenRouterModelsTest {

    private static final Set<String> PI_ANTHROPIC_LANE_IDS = Set.of(
        "anthropic/claude-fable-5", "anthropic/claude-fable-5.1",
        "anthropic/claude-haiku-4.5", "anthropic/claude-opus-4.1",
        "anthropic/claude-opus-4.5", "anthropic/claude-opus-4.6",
        "anthropic/claude-opus-4.7", "anthropic/claude-opus-4.8",
        "anthropic/claude-opus-5", "anthropic/claude-opus-5.5",
        "anthropic/claude-sonnet-4", "anthropic/claude-sonnet-4.5",
        "anthropic/claude-sonnet-4.6", "anthropic/claude-sonnet-5");

    private static com.pijava.ai.catalog.ModelInfo model(String id) {
        return OpenRouterModels.catalog()
            .find(ModelId.of("openrouter", id)).orElseThrow();
    }

    @Test
    void anthropicLaneCoversAllFourteenPiModels() {
        var ids = OpenRouterModels.anthropicLane().stream()
            .map(m -> m.id().modelName()).collect(java.util.stream.Collectors.toSet());

        assertThat(ids).isEqualTo(PI_ANTHROPIC_LANE_IDS);
        // 派发标记必带（缺席 ⇒ 走 completions 默认协议 ⇒ 发错车道）。
        assertThat(OpenRouterModels.anthropicLane())
            .allSatisfy(m -> assertThat(m.api()).isEqualTo("anthropic-messages"));
    }

    @Test
    void completionsSubsetRidesTheDefaultLane() {
        // api 缺席 ≙ provider 默认协议（openai-completions）——与 pi 数据的
        // "api":"openai-completions" 行为等价（原 docs/59 §4.3）。
        assertThat(OpenRouterModels.completionsLane())
            .allSatisfy(m -> assertThat(m.api()).isNull());
        // B105 的内置消费者族（cacheControlFormat 探测 :1632 命中 anthropic/ 前缀）。
        assertThat(OpenRouterModels.completionsLane())
            .extracting(m -> m.id().modelName())
            .contains("anthropic/claude-fable-5:batch", "openai/gpt-5.1",
                "deepseek/deepseek-chat", "google/gemini-2.5-pro");
    }

    @Test
    void anthropicCompatComesFromThePredicatesNotHardcodedValues() {
        // CatalogCompatRules.anthropic("openrouter", id) 的三谓词逐条对上 pi 生成数据
        // （原 docs/59 §3 的复用证据）。
        var fable5 = model("anthropic/claude-fable-5");
        assertThat(fable5.compat().forceAdaptiveThinking()).isTrue();
        assertThat(fable5.compat().supportsTemperature()).isTrue();

        var opus55 = model("anthropic/claude-opus-5.5");
        assertThat(opus55.compat().forceAdaptiveThinking()).isTrue();
        assertThat(opus55.compat().supportsTemperature()).isFalse();

        var haiku = model("anthropic/claude-haiku-4.5");
        assertThat(haiku.compat().forceAdaptiveThinking()).isFalse();
        assertThat(haiku.compat().supportsTemperature()).isTrue();

        // midConvo 谓词有 provider === "anthropic" 门 ⇒ openrouter 条目恒缺席（pi 数据同）。
        assertThat(OpenRouterModels.anthropicLane())
            .allSatisfy(m -> assertThat(m.compat().supportsMidConvoSystemMessages()).isNull());
    }

    @Test
    void transcribedDataMatchesThePiSnapshot() {
        var fable5 = model("anthropic/claude-fable-5");
        assertThat(fable5.displayName()).isEqualTo("Anthropic: Claude Fable 5");
        assertThat(fable5.maxInputTokens()).isEqualTo(1_000_000);
        assertThat(fable5.maxOutputTokens()).isEqualTo(128_000);
        assertThat(fable5.pricing().inputPrice()).isEqualTo(10.0);
        assertThat(fable5.pricing().outputPrice()).isEqualTo(50.0);
        assertThat(fable5.pricing().cacheReadPrice()).isEqualTo(1.0);
        assertThat(fable5.pricing().cacheWritePrice()).isEqualTo(12.5);
        assertThat(fable5.capabilities()).contains(
            ModelCapability.THINKING, ModelCapability.IMAGE_INPUT, ModelCapability.PROMPT_CACHING);
        // tlm {off:null, …}：off 显式不支持（anthropic 车道 :1179 判据的输入）。
        assertThat(fable5.thinkingLevelMap().explicitlyUnsupported(ModelThinkingLevel.off())).isTrue();
        assertThat(fable5.thinkingLevelMap().mapped(ModelThinkingLevel.parse("low").orElseThrow()))
            .contains("low");

        // 部分转录样本：cacheWrite 的非整齐小数逐字（0.083333）。
        var flash = model("google/gemini-2.5-flash");
        assertThat(flash.pricing().cacheWritePrice()).isEqualTo(0.083333);
        assertThat(flash.maxOutputTokens()).isEqualTo(65_535);
    }

    @Test
    void capabilitiesFollowThePiDataKeys() {
        // reasoning:false ⇒ 无 THINKING（openrouter 思考形状的门，pi :931）。
        assertThat(model("deepseek/deepseek-chat").capabilities())
            .doesNotContain(ModelCapability.THINKING);
        // input:["text"] ⇒ 无 IMAGE_INPUT。
        assertThat(model("deepseek/deepseek-r1").capabilities())
            .doesNotContain(ModelCapability.IMAGE_INPUT);
        // PROMPT_CACHING ⟺ cacheRead > 0。
        assertThat(model("openai/gpt-5.1").capabilities())
            .contains(ModelCapability.PROMPT_CACHING);
        assertThat(model("deepseek/deepseek-chat").capabilities())
            .doesNotContain(ModelCapability.PROMPT_CACHING);
    }

    @Test
    void completionsEntriesCarryTheCataloguedThinkingFormat() {
        // CatalogCompatRules 的 openrouter 臂（G8）：与探测同值的冗余照抄。
        assertThat(OpenRouterModels.completionsLane())
            .allSatisfy(m -> assertThat(m.compat().thinkingFormat())
                .isEqualTo(ThinkingFormat.OPENROUTER));
        // anthropic 车道条目不带 completions 车道的键（车道各自解析）。
        assertThat(model("anthropic/claude-fable-5").compat().thinkingFormat()).isNull();
    }

    @Test
    void batchIdsKeepTheirColonSuffixVerbatim() {
        // resolver 的精确匹配（原 docs/59 §4.7）靠目录里逐字的 ":batch" id。
        assertThat(model("anthropic/claude-fable-5:batch").displayName())
            .isEqualTo("Anthropic: Claude Fable 5 (batch)");
    }

    @Test
    void registeredInProviderCatalogAlongsideTheImagesProvider() {
        var names = ProviderCatalog.all().stream().map(p -> p.name()).toList();
        assertThat(names).contains("openrouter", "openrouter-images");
        assertThat(ProviderCatalog.allModels()
            .find(ModelId.of("openrouter", "anthropic/claude-fable-5"))).isPresent();
        // 两个 provider 的目录不串（images 条目仍在 openrouter-images 名下）。
        assertThat(ProviderCatalog.allModels()
            .find(ModelId.of("openrouter-images", "google/gemini-3-pro-image"))).isPresent();
    }

    @Test
    void catalogListingHasNoDuplicateIds() {
        var ids = OpenRouterModels.catalog().listModels().stream()
            .map(m -> m.id().provider() + "/" + m.id().modelName()).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat((List<?>) OpenRouterModels.catalog().listModels()).hasSize(24);
    }
}
