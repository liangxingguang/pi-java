package com.pijava.ai.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B156 守卫：{@link ModelInfo} 有 16 个组件，每个「复制部分字段」的方法都必须
 * 逐个搬运其余组件；漏一个就会在个性化刷新时静默丢数据。
 */
class ModelInfoCopyTest {

    private static ModelInfo everyField() {
        return new ModelInfo(
            ModelId.of("openrouter", "model"),
            "Model",
            Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE),
            1000,
            200,
            true,
            new PricingInfo(1, 2, 3, 4, List.of(new PricingInfo.CostTier(10, 5, 6, 7, 8))),
            ThinkingLevelMap.of(Map.of(ModelThinkingLevel.parse("high").orElseThrow(),
                Optional.of("high"))),
            Map.of("x-header", "v"),
            Map.of("temperature", 0.5),
            ModelCompat.NONE,
            "openai-completions",
            "https://example.test/v1",
            new PromptCacheLifetimes(300, 3600),
            new InputLimits(100, new InputLimits.ImageLimits(
                new InputLimits.ResizeOptions(2000, 2000, 4718592, 80), 10, 20)),
            Map.of(ModelThinkingLevel.parse("low").orElseThrow(), Map.of("top_p", 0.9)));
    }

    @Test
    void withLimitsAndPricingChangesOnlyThoseThreeComponents() {
        var original = everyField();
        var pricing = new PricingInfo(9, 8, 7, 6, List.of());

        var updated = original.withLimitsAndPricing(123_000, 4_096, pricing);

        assertThat(updated.maxInputTokens()).isEqualTo(123_000);
        assertThat(updated.maxOutputTokens()).isEqualTo(4_096);
        assertThat(updated.pricing()).isEqualTo(pricing);
        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.displayName()).isEqualTo(original.displayName());
        assertThat(updated.capabilities()).isEqualTo(original.capabilities());
        assertThat(updated.deprecated()).isEqualTo(original.deprecated());
        assertThat(updated.thinkingLevelMap()).isEqualTo(original.thinkingLevelMap());
        assertThat(updated.headers()).isEqualTo(original.headers());
        assertThat(updated.samplingParams()).isEqualTo(original.samplingParams());
        assertThat(updated.compat()).isEqualTo(original.compat());
        assertThat(updated.api()).isEqualTo(original.api());
        assertThat(updated.baseUrl()).isEqualTo(original.baseUrl());
        assertThat(updated.promptCache()).isEqualTo(original.promptCache());
        assertThat(updated.inputLimits()).isEqualTo(original.inputLimits());
        assertThat(updated.samplingParamsByThinkingLevel())
            .isEqualTo(original.samplingParamsByThinkingLevel());
    }
}
