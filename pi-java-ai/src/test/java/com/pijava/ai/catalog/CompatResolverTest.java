package com.pijava.ai.catalog;

import java.util.Map;
import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CompatResolver} — pi 的五份 per-api compat 解析函数在 java 上的合一实现
 * （包 A7a，{@code docs/53}）。
 *
 * <p>逐条期望值来自 pi 的源码（{@code detectCompat:1583-1679}、
 * {@code getCompat:1685-1721}、{@code getAnthropicCompat:206-219}、
 * {@code openai-responses.ts:68-81}）与 **pi 自己的测试**
 * （{@code packages/ai/test/providers.test.ts}，锚点上 26/26 绿）。</p>
 */
class CompatResolverTest {

    private static ModelInfo model(String provider, String id) {
        return model(provider, id, ModelCompat.NONE);
    }

    private static ModelInfo model(String provider, String id, ModelCompat compat) {
        return new ModelInfo(ModelId.of(provider, id), id, Set.of(ModelCapability.TEXT),
            128_000, 8_192, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), compat);
    }

    // ── completions 的探测（pi detectCompat）─────────────────────

    @Test
    void completionsDetectsTheDeepSeekProvider() {
        var compat = CompatResolver.forCompletions(
            model("deepseek", "deepseek-v4-pro"), "https://api.deepseek.com");

        assertThat(compat.requiresReasoningContentOnAssistantMessages()).isTrue();
        assertThat(compat.maxTokensField()).isEqualTo(MaxTokensField.MAX_TOKENS);
        assertThat(compat.supportsStore()).isFalse();
        assertThat(compat.supportsDeveloperRole()).isFalse();
    }

    @Test
    void completionsDetectsDeepSeekFromTheBaseUrlAlone() {
        // pi detectCompat:1601 —— provider 名**或** baseUrl 子串，二者命中其一即可。
        var compat = CompatResolver.forCompletions(
            model("my-relay", "some-model"), "https://relay.deepseek.com/v1");

        assertThat(compat.requiresReasoningContentOnAssistantMessages()).isTrue();
        assertThat(compat.maxTokensField()).isEqualTo(MaxTokensField.MAX_TOKENS);
    }

    @Test
    void completionsDetectsTheZaiEndpoints() {
        var compat = CompatResolver.forCompletions(
            model("", "glm-5.2"), "https://api.z.ai/api/paas/v4");

        assertThat(compat.requiresReasoningContentOnAssistantMessages()).isFalse();
        assertThat(compat.maxTokensField()).isEqualTo(MaxTokensField.MAX_TOKENS);
        assertThat(compat.supportsStore()).isFalse();
        assertThat(compat.supportsDeveloperRole()).isFalse();
    }

    @Test
    void completionsDefaultsToTheOpenAiShapes() {
        var compat = CompatResolver.forCompletions(
            model("openai", "gpt-5"), "https://api.openai.com/v1");

        assertThat(compat.requiresReasoningContentOnAssistantMessages()).isFalse();
        assertThat(compat.maxTokensField()).isEqualTo(MaxTokensField.MAX_COMPLETION_TOKENS);
        assertThat(compat.supportsStore()).isTrue();
        assertThat(compat.supportsDeveloperRole()).isTrue();
    }

    @Test
    void openRouterKeepsTheDeveloperRoleForVendorModelsOnly() {
        // pi detectCompat:1630 —— OpenRouter 上只有 anthropic/* 与 openai/* 走 developer 角色。
        var vendor = CompatResolver.forCompletions(
            model("openrouter", "anthropic/claude-opus-4-8"), "https://openrouter.ai/api/v1");
        var own = CompatResolver.forCompletions(
            model("openrouter", "z-ai/glm-5.2"), "https://openrouter.ai/api/v1");

        assertThat(vendor.supportsDeveloperRole()).isTrue();
        assertThat(own.supportsDeveloperRole()).isFalse();
        // ⚠️ 但 **store 是开的**：pi 的 `isNonStandard`（`detectCompat:1603-1617`）里
        // **没有** `isOpenRouter` —— 直觉上「OpenRouter 是聚合端点所以非标准」是**错的**。
        // 我不是靠读源码定这一条的：pi 的生成目录只写**与缺省不同的差量**，而
        // `OPENAI_COMPLETIONS_DEFAULT_COMPAT.supportsStore = true` ⇒ 若探测给 `false`，
        // 378 个 openrouter 模型里总会有一个写出这个键。实测 **0/378** 带它。
        assertThat(vendor.supportsStore()).isTrue();
        assertThat(own.supportsStore()).isTrue();
    }

    @Test
    void explicitCompatWinsOverTheDetection() {
        // pi getCompat 的 `explicit ?? detected`（docs/53 §2 P3）。
        var explicit = new ModelCompat(false, false, true, false, null, null, null, null, null,
            true, MaxTokensField.MAX_COMPLETION_TOKENS, true, true, null);
        var compat = CompatResolver.forCompletions(
            model("deepseek", "deepseek-v4-pro", explicit), "https://api.deepseek.com");

        assertThat(compat.requiresReasoningContentOnAssistantMessages()).isFalse();
        assertThat(compat.maxTokensField()).isEqualTo(MaxTokensField.MAX_COMPLETION_TOKENS);
        assertThat(compat.supportsStore()).isTrue();
        assertThat(compat.supportsDeveloperRole()).isTrue();
    }

    @Test
    void completionsFillsItsTwoMidConvoFlagsAndLeavesTheRest() {
        var compat = CompatResolver.forCompletions(
            model("openai", "gpt-5"), "https://api.openai.com/v1");

        // 本车道的两个（pi 的 completions 接口里有它、探测给常量 false）⇒ 已确定。
        assertThat(compat.supportsMidConvoSystemMessages()).isFalse();
        assertThat(compat.supportsMidConvoToolAdditions()).isFalse();
        // 不在 completions 接口里的三个 ⇒ 原样透传（今天没有 completions 读点读它们）。
        assertThat(compat.supportsMidConvoToolChanges()).isNull();
        assertThat(compat.supportsAdditionalTools()).isNull();
        assertThat(compat.supportsToolSearch()).isNull();
    }

    @Test
    void completionsKeepsTheExplicitMidConvoTrue() {
        var explicit = new ModelCompat(false, null, true, false, true, true, null, null, null);
        var compat = CompatResolver.forCompletions(
            model("openai", "gpt-5", explicit), "https://api.openai.com/v1");

        assertThat(compat.supportsMidConvoSystemMessages()).isTrue();
        assertThat(compat.supportsMidConvoToolAdditions()).isTrue();
    }

    // ── anthropic（pi getAnthropicCompat：无 URL 探测）───────────

    @Test
    void anthropicFillsItsTwoFlagsWithFalse() {
        var compat = CompatResolver.forAnthropic(model("anthropic", "claude-sonnet-4-6"));

        assertThat(compat.supportsMidConvoSystemMessages()).isFalse();
        assertThat(compat.supportsMidConvoToolChanges()).isFalse();
        assertThat(compat.supportsMidConvoToolAdditions()).isNull();
    }

    @Test
    void anthropicKeepsTheCatalogueFlags() {
        // 内置目录标的那两个（claude-fable-5 / claude-opus-4-8，docs/53 §4.2）。
        var catalogue = new ModelCompat(false, null, true, true, true, null, true, null, null);
        var compat = CompatResolver.forAnthropic(
            model("anthropic", "claude-fable-5", catalogue));

        assertThat(compat.supportsMidConvoSystemMessages()).isTrue();
        assertThat(compat.supportsMidConvoToolChanges()).isTrue();
        assertThat(compat.forceAdaptiveThinking()).isTrue();
    }

    @Test
    void anthropicHasNoUrlDetectionAtAll() {
        // ⚠️ pi 的 anthropic 车道只按 isOpenRouter 判 session affinity（java 不携带）⇒
        // 一个 openrouter 上的 anthropic 模型不会因此得到任何 mid-convo 能力。
        var compat = CompatResolver.forAnthropic(
            model("openrouter", "anthropic/claude-sonnet-4-6"));

        assertThat(compat.supportsMidConvoSystemMessages()).isFalse();
    }

    // ── responses（车道的 strict 缺省两侧相反）───────────────────

    @Test
    void responsesTakesTheLaneDefaultForStrictMode() {
        var compat = CompatResolver.forResponses(model("openai", "gpt-5"), false);
        var azure = CompatResolver.forResponses(model("azure-openai-responses", "gpt-5"), true);

        assertThat(compat.supportsStrictMode()).isFalse();
        assertThat(azure.supportsStrictMode()).isTrue();
    }

    @Test
    void responsesLetsTheExplicitStrictModeBeatTheLaneDefault() {
        var explicit = new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, false);
        var compat = CompatResolver.forResponses(model("openai", "gpt-5", explicit), true);

        assertThat(compat.supportsStrictMode()).isFalse();
    }

    @Test
    void responsesFillsTheToolAnchoringFlags() {
        var compat = CompatResolver.forResponses(model("openai", "gpt-5"), false);

        assertThat(compat.supportsMidConvoSystemMessages()).isFalse();
        assertThat(compat.supportsAdditionalTools()).isFalse();
        assertThat(compat.supportsToolSearch()).isFalse();
        assertThat(compat.supportsMidConvoToolChanges()).isNull();
    }

    // ── mistral（pi 直读 partial ⇒ ?? false）───────────────────

    @Test
    void mistralFillsOnlyItsOneFlag() {
        var compat = CompatResolver.forMistral(model("mistral", "mistral-large-latest"));

        assertThat(compat.supportsMidConvoSystemMessages()).isFalse();
        assertThat(compat.supportsAdditionalTools()).isNull();
        assertThat(compat.supportsStrictMode()).isNull();
    }

    // ── 无模型上下文 ──────────────────────────────────────────

    @Test
    void aNullModelStillDetectsFromTheBaseUrl() {
        var compat = CompatResolver.forCompletions(null, "https://api.deepseek.com");

        assertThat(compat.requiresReasoningContentOnAssistantMessages()).isTrue();
        assertThat(compat.maxTokensField()).isEqualTo(MaxTokensField.MAX_TOKENS);
        assertThat(compat.supportsStore()).isFalse();
    }

    @Test
    void aNullBaseUrlIsTreatedAsTheStandardEndpoint() {
        var compat = CompatResolver.forCompletions(model("openai", "gpt-5"), null);

        assertThat(compat.supportsStore()).isTrue();
        assertThat(compat.maxTokensField()).isEqualTo(MaxTokensField.MAX_COMPLETION_TOKENS);
    }

    // ── 记录形状 ─────────────────────────────────────────────

    @Test
    void thePreA7NineArgumentConstructorLeavesTheNewFlagsAtTheirDefaults() {
        var legacy = new ModelCompat(false, null, true, false, null, null, null, null, null);

        assertThat(legacy.supportsTemperature()).isTrue();
        assertThat(legacy.maxTokensField()).isNull();
        assertThat(legacy.supportsStore()).isNull();
        assertThat(legacy.supportsDeveloperRole()).isNull();
        assertThat(legacy.supportsStrictMode()).isNull();
    }

    @Test
    void maxTokensFieldWireNamesArePisStrings() {
        assertThat(MaxTokensField.MAX_TOKENS.wireName()).isEqualTo("max_tokens");
        assertThat(MaxTokensField.MAX_COMPLETION_TOKENS.wireName())
            .isEqualTo("max_completion_tokens");
    }
}
