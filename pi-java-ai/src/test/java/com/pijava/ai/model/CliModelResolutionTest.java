package com.pijava.ai.model;

import java.util.List;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W6b（docs/25 §7；pi {@code test/model-resolver.test.ts:483-698}）：CLI 单模型
 * 解析——未知 provider／歧义（含 authHint 三臂）／not-found／自定义 id 五类文案逐字。
 */
class CliModelResolutionTest {

    private static ModelInfo info(String provider, String id) {
        return new ModelInfo(ModelId.of(provider, id), id, Set.of(ModelCapability.TEXT),
            128_000, 8192, true, PricingInfo.UNKNOWN);
    }

    private static final ModelInfo SONNET = info("anthropic", "claude-sonnet-4-5");
    private static final ModelInfo GPT_4O = info("openai", "gpt-4o");
    private static final ModelInfo OR_OPUS = info("openrouter", "anthropic/claude-opus-5.5");
    private static final ModelInfo VERCEL_OPUS = info("vercel-ai-gateway", "anthropic/claude-opus-5.5");
    private static final List<ModelInfo> MODELS =
        List.of(SONNET, GPT_4O, OR_OPUS, VERCEL_OPUS);

    private static CliModelResolution.Result resolve(String provider, String model,
            boolean thinking, boolean inlineKey, Set<String> configured) {
        return CliModelResolution.resolve(new CliModelResolution.Request(provider, model,
            thinking, inlineKey, MODELS, configured::contains));
    }

    @Test
    void absentModelResolvesToNothing() {
        var result = resolve(null, null, false, false, Set.of());

        assertThat(result.model()).isNull();
        assertThat(result.error()).isNull();
        assertThat(result.warning()).isNull();
    }

    @Test
    void catalogHitHasNoWarning() {
        var result = resolve(null, "gpt-4o", false, false, Set.of());

        assertThat(result.error()).isNull();
        assertThat(result.model()).isEqualTo(GPT_4O);
        assertThat(result.warning()).isNull();
    }

    @Test
    void unknownProviderTextIsVerbatim() {
        var result = resolve("nope", "gpt-4o", false, false, Set.of());

        assertThat(result.model()).isNull();
        assertThat(result.error()).isEqualTo("Unknown provider \"nope\". "
            + "Use --list-models to see available providers/models.");
    }

    @Test
    void noModelsAvailableTextIsVerbatim() {
        var result = CliModelResolution.resolve(new CliModelResolution.Request(null, "gpt-4o",
            false, false, List.of(), provider -> false));

        assertThat(result.error()).isEqualTo(
            "No models available. Check your installation or add models to models.json.");
    }

    @Test
    void explicitProviderSelectsAuthenticatedGateway() {
        for (var thinking : List.of(false, true)) {
            var result = resolve(null,
                thinking ? "anthropic/claude-opus-5.5:high" : "anthropic/claude-opus-5.5",
                thinking, false, Set.of("vercel-ai-gateway"));

            assertThat(result.error()).isNull();
            assertThat(result.model()).isEqualTo(VERCEL_OPUS);
            assertThat(result.thinkingLevel()).isEqualTo(thinking ? "high" : null);
        }
    }

    @Test
    void ambiguityReportsSortedProviderIdsAndNoAuthHint() {
        var result = resolve(null, "anthropic/claude-opus-5.5", false, false, Set.of());

        assertThat(result.model()).isNull();
        assertThat(result.error()).isEqualTo("Model \"anthropic/claude-opus-5.5\" is ambiguous "
            + "across providers: openrouter/anthropic/claude-opus-5.5, "
            + "vercel-ai-gateway/anthropic/claude-opus-5.5. "
            + "No matching provider is authenticated. Use --provider or provider/model.");
    }

    @Test
    void ambiguityWithSeveralAuthenticatedProvidersUsesItsHint() {
        var result = resolve(null, "anthropic/claude-opus-5.5", false, false,
            Set.of("openrouter", "vercel-ai-gateway"));

        assertThat(result.error()).contains(
            "More than one matching provider is authenticated. Use --provider or provider/model.");
    }

    @Test
    void inlineApiKeyDoesNotLetStoredCredentialsChoose() {
        var result = resolve(null, "anthropic/claude-opus-5.5", false, true,
            Set.of("vercel-ai-gateway"));

        assertThat(result.model()).isNull();
        assertThat(result.error()).contains("--api-key does not choose a provider.");
    }

    @Test
    void bareUnknownModelIsAnErrorNotACustomFallback() {
        // 2026-11-05 裁决：裸未知 --model 不再是「默认 provider 下的自定义模型」。
        var result = resolve(null, "ghost-model", false, false, Set.of());

        assertThat(result.model()).isNull();
        assertThat(result.customId()).isNull();
        assertThat(result.error()).isEqualTo("Model \"ghost-model\" not found. "
            + "Use --list-models to see available models.");
    }

    @Test
    void explicitProviderAllowsCustomIdWithWarning() {
        var result = resolve("openrouter", "openrouter/openai/ghost-model", false, false,
            Set.of());

        assertThat(result.error()).isNull();
        assertThat(result.customId()).isEqualTo(ModelId.of("openrouter", "openai/ghost-model"));
        assertThat(result.warning()).isEqualTo("Model \"openai/ghost-model\" not found for "
            + "provider \"openrouter\". Using custom model id.");
    }

    @Test
    void thinkingSuffixStaysInCustomIdWhenThinkingIsExplicit() {
        var result = resolve(null, "openrouter/ghost:high", true, false, Set.of());

        assertThat(result.customId()).isEqualTo(ModelId.of("openrouter", "ghost:high"));
        assertThat(result.thinkingLevel()).isNull();
        assertThat(result.warning()).contains("Model \"ghost:high\" not found for provider");
    }

    @Test
    void thinkingSuffixIsSplitWhenThinkingIsNotExplicit() {
        var result = resolve(null, "openrouter/ghost:high", false, false, Set.of());

        assertThat(result.customId()).isEqualTo(ModelId.of("openrouter", "ghost"));
        assertThat(result.thinkingLevel()).isEqualTo("high");
        assertThat(result.warning()).isEqualTo("Model \"ghost\" not found for "
            + "provider \"openrouter\". Using custom model id.");
    }

    /**
     * D227（Java 方言；pi 无此臂）：{@code provider/id} 形状而前缀 provider 未编目
     * ⇒ 落到该前缀——虚拟模型（extension 注册的 {@code jev/auto}）与用户自建
     * provider 靠这条走。{@code --provider} 不改变该判定。
     */
    @Test
    void unlistedProviderPrefixStillResolvesAsCustomId() {
        var result = CliModelResolution.resolve(new CliModelResolution.Request(
            "faux-vm", "jev/auto", false, false, MODELS, provider -> false,
            "faux-vm"::equals));

        assertThat(result.error()).isNull();
        assertThat(result.customId()).isEqualTo(ModelId.of("jev", "auto"));
        assertThat(result.warning()).isEqualTo(
            "Model \"auto\" not found for provider \"jev\". Using custom model id.");
    }

    /** 已知 provider（运行时注册）的显式 --provider 仍走运行时自定义 id 臂。 */
    @Test
    void knownRuntimeProviderAllowsCustomId() {
        var result = CliModelResolution.resolve(new CliModelResolution.Request(
            "faux-tree", "hello", false, false, MODELS, provider -> false,
            "faux-tree"::equals));

        assertThat(result.error()).isNull();
        assertThat(result.customId()).isEqualTo(ModelId.of("faux-tree", "hello"));
        assertThat(result.warning()).isEqualTo(
            "Model \"hello\" not found for provider \"faux-tree\". Using custom model id.");
    }

    @Test
    void invalidSuffixStaysInTheCustomId() {
        var result = resolve("openai", "gpt-4o:extended", false, false, Set.of());

        assertThat(result.error()).isNull();
        assertThat(result.customId()).isEqualTo(ModelId.of("openai", "gpt-4o:extended"));
    }
}
