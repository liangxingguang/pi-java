package com.pijava.ai.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W6a（docs/25 §7；pi {@code test/model-resolver.test.ts:92-348} 的
 * {@code referenceCases} 全表）：解析核——候选先收集后选 provider、可用性只破平、
 * 思考后缀三态、歧义显式报出；每个案例都跑正反两种目录序（目录序永不决定结果）。
 */
class ModelReferenceResolverTest {

    private static ModelInfo ref(String provider, String id) {
        return new ModelInfo(ModelId.of(provider, id), id, Set.of(ModelCapability.TEXT),
            128_000, 8192, true, PricingInfo.UNKNOWN);
    }

    private static final List<ModelInfo> CATALOG = List.of(
        ref("anthropic", "claude-sonnet-4-5"),
        ref("anthropic", "claude-sonnet-4-5-20250929"),
        ref("anthropic", "claude-opus-4-8"),
        ref("anthropic", "exact"),
        // 模糊命中 "shared"；精确 "shared" 歧义时不得回落到它。
        ref("anthropic", "shared-pro"),
        ref("openai", "gpt-4o"),
        ref("openrouter", "qwen/qwen3-coder:exacto"),
        ref("openrouter", "openai/gpt-4o:extended"),
        ref("gw-a", "shared"),
        ref("gw-a", "anthropic/future"),
        ref("gw-a", "anthropic/exact"),
        ref("gw-a", "anthropic/claude-opus-4.8"),
        ref("gw-a", "anthropic/claude-opus"),
        ref("gw-b", "shared"),
        ref("gw-b", "anthropic/future"),
        ref("gw-b", "claude-opus-4-8"),
        ref("gw-b", "gw-b/free"),
        ref("gw-b", "zz:free"),
        // URL 派生、自身含 "/" 的 provider id（pi #11394）。
        ref("llama=http://host:10800", "unsloth/qwen-gguf:BF16"));

    private static String refOf(ModelInfo model) {
        return model.id().provider() + "/" + model.id().modelName();
    }

    /** 断言一次解析；两种目录序都必须同结果。 */
    private void check(String text, String provider,
                       ModelReferenceResolver.ThinkingSuffix suffix, boolean fuzzy,
                       Set<String> usable, String expectedRef, String expectedLevel,
                       List<String> expectedAmbiguous, int expectedUsableCount) {
        for (var catalog : List.of(CATALOG, reversed())) {
            var result = ModelReferenceResolver.resolve(
                new ModelReferenceResolver.Request(text, provider, suffix, fuzzy), catalog,
                usable::contains);
            if (expectedRef != null) {
                assertThat(result).as("text=%s", text)
                    .isInstanceOf(ModelReferenceResolver.Result.Resolved.class);
                var resolved = (ModelReferenceResolver.Result.Resolved) result;
                assertThat(refOf(resolved.model())).as("text=%s", text).isEqualTo(expectedRef);
                assertThat(resolved.thinkingLevel()).as("text=%s", text).isEqualTo(expectedLevel);
            } else if (expectedAmbiguous != null) {
                assertThat(result).as("text=%s", text)
                    .isInstanceOf(ModelReferenceResolver.Result.Ambiguous.class);
                var ambiguous = (ModelReferenceResolver.Result.Ambiguous) result;
                assertThat(ambiguous.candidates().stream().map(ModelReferenceResolverTest::refOf)
                    .sorted().toList()).as("text=%s", text).isEqualTo(expectedAmbiguous);
                assertThat(ambiguous.usableCount()).as("text=%s", text)
                    .isEqualTo(expectedUsableCount);
            } else {
                assertThat(result).as("text=%s", text)
                    .isInstanceOf(ModelReferenceResolver.Result.NotFound.class);
            }
        }
    }

    private static List<ModelInfo> reversed() {
        var copy = new ArrayList<>(CATALOG);
        java.util.Collections.reverse(copy);
        return List.copyOf(copy);
    }

    private void resolved(String text, String model, String level) {
        check(text, null, ModelReferenceResolver.ThinkingSuffix.STRICT, true,
            Set.of(), model, level, null, 0);
    }

    private void resolvedUsable(String text, Set<String> usable, String model, String level) {
        check(text, null, ModelReferenceResolver.ThinkingSuffix.STRICT, true,
            usable, model, level, null, 0);
    }

    private void ambiguous(String text, Set<String> usable, List<String> candidates,
                           int usableCount) {
        check(text, null, ModelReferenceResolver.ThinkingSuffix.STRICT, true,
            usable, null, null, candidates, usableCount);
    }

    private void notFound(String text) {
        check(text, null, ModelReferenceResolver.ThinkingSuffix.STRICT, true,
            Set.of(), null, null, null, 0);
    }

    // ── 引用语法 ─────────────────────────────────────────────────

    @Test
    void exactIdAndCaseInsensitivity() {
        resolved("claude-sonnet-4-5", "anthropic/claude-sonnet-4-5", null);
        resolved("GPT-4O", "openai/gpt-4o", null);
    }

    @Test
    void fuzzyPrefersAliasesOverDatedIds() {
        resolved("sonnet", "anthropic/claude-sonnet-4-5", null);
    }

    @Test
    void thinkingSuffixIsParsed() {
        resolved("sonnet:high", "anthropic/claude-sonnet-4-5", "high");
    }

    @Test
    void colonInLiteralIdIsNotAThinkingSuffix() {
        resolved("qwen/qwen3-coder:exacto", "openrouter/qwen/qwen3-coder:exacto", null);
        resolved("openrouter/qwen/qwen3-coder:exacto:high",
            "openrouter/qwen/qwen3-coder:exacto", "high");
    }

    @Test
    void providerIdContainingSlash() {
        resolved("llama=http://host:10800/unsloth/qwen-gguf:BF16",
            "llama=http://host:10800/unsloth/qwen-gguf:BF16", null);
        resolved("llama=http://host:10800/unsloth/qwen-gguf:BF16:high",
            "llama=http://host:10800/unsloth/qwen-gguf:BF16", "high");
    }

    @Test
    void rawIdWinsWhenProviderPrefixNamesNoMatchingModel() {
        resolved("openai/gpt-4o:extended", "openrouter/openai/gpt-4o:extended", null);
    }

    @Test
    void fuzzySearchesThePrefixedProviderFirst() {
        resolved("openrouter/qwen", "openrouter/qwen/qwen3-coder:exacto", null);
    }

    @Test
    void invalidSuffixHandledPerMode() {
        // strict：非法后缀留在 id 里 ⇒ 未命中。
        notFound("gpt-4o:random");
        // lenient：丢弃并警告。
        var lenient = ModelReferenceResolver.resolve(
            ModelReferenceResolver.Request.lenient("gpt-4o:random"), CATALOG, p -> false);
        assertThat(lenient).isInstanceOf(ModelReferenceResolver.Result.Resolved.class);
        var resolved = (ModelReferenceResolver.Result.Resolved) lenient;
        assertThat(refOf(resolved.model())).isEqualTo("openai/gpt-4o");
        assertThat(resolved.warning()).isEqualTo(
            "Invalid thinking level \"random\" in pattern \"gpt-4o:random\". Using default instead.");
    }

    @Test
    void lenientInvalidOuterSuffixDiscardsInnerThinkingLevel() {
        var result = ModelReferenceResolver.resolve(
            ModelReferenceResolver.Request.lenient("qwen/qwen3-coder:exacto:high:random"),
            CATALOG, p -> false);
        var resolved = (ModelReferenceResolver.Result.Resolved) result;
        assertThat(refOf(resolved.model())).isEqualTo("openrouter/qwen/qwen3-coder:exacto");
        assertThat(resolved.thinkingLevel()).isNull();
        assertThat(resolved.warning()).contains("Invalid thinking level \"random\"");
    }

    @Test
    void noneKeepsSuffixAndExactOnlySkipsFuzzy() {
        check("gpt-4o:high", null, ModelReferenceResolver.ThinkingSuffix.NONE, true,
            Set.of(), null, null, null, 0);
        check("sonnet", null, ModelReferenceResolver.ThinkingSuffix.STRICT, false,
            Set.of(), null, null, null, 0);
        notFound("  ");
    }

    // ── 跨 provider 精确匹配 ─────────────────────────────────────

    @Test
    void soleUsableProviderWins() {
        resolvedUsable("shared", Set.of("gw-b"), "gw-b/shared", null);
    }

    @Test
    void noUsableProviderIsAmbiguousInsteadOfFuzzy() {
        ambiguous("shared", Set.of(), List.of("gw-a/shared", "gw-b/shared"), 0);
    }

    @Test
    void severalUsableProvidersAreAmbiguous() {
        ambiguous("shared", Set.of("gw-a", "gw-b"),
            List.of("gw-a/shared", "gw-b/shared"), 2);
    }

    @Test
    void thinkingSuffixDoesNotChangeProviderSelection() {
        resolvedUsable("shared:high", Set.of("gw-b"), "gw-b/shared", "high");
        ambiguous("shared:high", Set.of("gw-a", "gw-b"),
            List.of("gw-a/shared", "gw-b/shared"), 2);
    }

    @Test
    void providerPrefixWithoutDirectModelSelectsUsableRawId() {
        resolvedUsable("anthropic/future", Set.of("gw-b"), "gw-b/anthropic/future", null);
    }

    @Test
    void usableQualifiedMatchBeatsUsableRawId() {
        resolvedUsable("anthropic/exact", Set.of("anthropic", "gw-a"), "anthropic/exact", null);
    }

    @Test
    void usableRawIdBeatsUnusableQualifiedMatch() {
        resolvedUsable("anthropic/exact", Set.of("gw-a"), "gw-a/anthropic/exact", null);
        resolved("anthropic/exact", "anthropic/exact", null);
    }

    @Test
    void namedProviderFuzzyVersusRawId() {
        resolvedUsable("anthropic/claude-opus", Set.of("anthropic"),
            "anthropic/claude-opus-4-8", null);
        resolvedUsable("anthropic/claude-opus", Set.of("gw-a"),
            "gw-a/anthropic/claude-opus", null);
        resolved("anthropic/claude-opus", "anthropic/claude-opus-4-8", null);
    }

    @Test
    void namedProviderLiteralIdBeatsItsFuzzyMatches() {
        resolved("gw-b/free", "gw-b/gw-b/free", null);
    }

    // ── 跨 provider 模糊匹配 ─────────────────────────────────────

    @Test
    void fuzzyPrefersUsableProviders() {
        // "opus" 的模糊集里 gw-a 只有 anthropic/claude-opus-4.8（降序首条）。
        resolvedUsable("opus", Set.of("gw-a"), "gw-a/anthropic/claude-opus-4.8", null);
    }

    @Test
    void fuzzyIdTieAcrossUsableProvidersIsAmbiguous() {
        ambiguous("opus", Set.of("anthropic", "gw-a", "gw-b"),
            List.of("anthropic/claude-opus-4-8", "gw-b/claude-opus-4-8"), 2);
    }

    @Test
    void fuzzyIdTieResolvesToSoleUsableProvider() {
        resolvedUsable("opus", Set.of("anthropic"), "anthropic/claude-opus-4-8", null);
    }

    // ── 显式 provider 约束 ───────────────────────────────────────

    @Test
    void explicitProviderConstraint() {
        check("shared", "gw-a", ModelReferenceResolver.ThinkingSuffix.STRICT, true,
            Set.of("gw-b"), "gw-a/shared", null, null, 0);
        check("GW-B/shared", "gw-b", ModelReferenceResolver.ThinkingSuffix.STRICT, true,
            Set.of(), "gw-b/shared", null, null, 0);
        check("4o", "openai", ModelReferenceResolver.ThinkingSuffix.STRICT, true,
            Set.of(), "openai/gpt-4o", null, null, 0);
        check("gpt-4o", "gw-a", ModelReferenceResolver.ThinkingSuffix.STRICT, true,
            Set.of(), null, null, null, 0);
    }

    // ── 薄包装与自定义 id 目标 ───────────────────────────────────

    @Test
    void exactMatchWrapper() {
        assertThat(ModelReferenceResolver.findExactMatch("openai/gpt-4o", CATALOG))
            .map(ModelReferenceResolverTest::refOf).contains("openai/gpt-4o");
        assertThat(ModelReferenceResolver.findExactMatch("dup", CATALOG)).isEmpty();
    }

    @Test
    void notFoundCarriesTargetForExplicitProvider() {
        var result = ModelReferenceResolver.resolve(
            new ModelReferenceResolver.Request("neuralwatt/ghost:high", null,
                ModelReferenceResolver.ThinkingSuffix.STRICT, true),
            List.of(ref("neuralwatt", "some-base-model")), p -> true);

        var notFound = (ModelReferenceResolver.Result.NotFound) result;
        var target = notFound.target().orElseThrow();
        assertThat(target.provider()).isEqualTo("neuralwatt");
        assertThat(target.modelText()).isEqualTo("ghost:high");
        assertThat(target.modelId()).isEqualTo("ghost");
        assertThat(target.thinkingLevel()).isEqualTo("high");
        assertThat(target.providerModels()).hasSize(1);
    }
}
