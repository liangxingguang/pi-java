package com.pijava.ai.model;

import java.util.List;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModelPatterns} 夹具：移植 pi {@code test/model-resolver.test.ts} 的
 * parseModelPattern/tryMatchModel 案例与 mock 模型形状。
 */
class ModelPatternsTest {

    private static ModelInfo info(String provider, String id, String name) {
        return new ModelInfo(ModelId.of(provider, id), name, Set.of(ModelCapability.TEXT),
            128_000, 4096, false, PricingInfo.UNKNOWN);
    }

    /** pi mock：两套常规模型 + 两个 openrouter 冒号 id + 一对跨家裸 id 重复。 */
    private static final List<ModelInfo> MODELS = List.of(
        info("anthropic", "claude-sonnet-4-5", "Claude Sonnet 4.5"),
        info("anthropic", "claude-sonnet-4-5-20250929", "Claude Sonnet 4.5 (dated)"),
        info("openai", "gpt-4o", "GPT-4o"),
        info("openrouter", "qwen/qwen3-coder:exacto", "Qwen3 Coder Exacto"),
        info("openrouter", "openai/gpt-4o:extended", "GPT-4o Extended"),
        info("x", "dup-id", "Dup X"),
        info("y", "dup-id", "Dup Y"));

    // ── findExactReference：三段短路与唯一性 ──────────────────────────────

    @Test
    void exactCanonicalReference() {
        assertThat(ModelPatterns.findExactReference("openai/gpt-4o", MODELS).orElseThrow().id())
            .isEqualTo(ModelId.of("openai", "gpt-4o"));
    }

    @Test
    void exactIsCaseInsensitive() {
        assertThat(ModelPatterns.findExactReference("OPENAI/GPT-4O", MODELS)).isPresent();
        assertThat(ModelPatterns.findExactReference("GPT-4O", MODELS)).isPresent();
    }

    @Test
    void ambiguousBareIdAcrossProvidersDoesNotMatch() {
        assertThat(ModelPatterns.findExactReference("dup-id", MODELS)).isEmpty();
    }

    @Test
    void blankReferenceDoesNotMatch() {
        assertThat(ModelPatterns.findExactReference("  ", MODELS)).isEmpty();
    }

    // ── tryMatch：子串、name、alias/dated 排序 ────────────────────────────

    @Test
    void substringPrefersAliasOverDatedAndSortsDescending() {
        var hit = ModelPatterns.tryMatch("sonnet", MODELS).orElseThrow();
        assertThat(hit.id()).isEqualTo(ModelId.of("anthropic", "claude-sonnet-4-5"));
    }

    @Test
    void substringMatchesDisplayName() {
        // "dated" 只出现在显示名 "Claude Sonnet 4.5 (dated)" 里，id 不含。
        var hit = ModelPatterns.tryMatch("dated", MODELS).orElseThrow();
        assertThat(hit.id().modelName()).isEqualTo("claude-sonnet-4-5-20250929");
    }

    @Test
    void ambiguousExactFallsThroughToSubstringAndKeepsCatalogOrder() {
        // docs/12 §4①：裸 id 歧义不硬错；子串路同 id 降序相等 ⇒ 稳定序取目录首条（x 在 y 前）。
        var hit = ModelPatterns.tryMatch("dup-id", MODELS).orElseThrow();
        assertThat(hit.id()).isEqualTo(ModelId.of("x", "dup-id"));
    }

    @Test
    void noSubstringMatch() {
        assertThat(ModelPatterns.tryMatch("nonexistent", MODELS)).isEmpty();
    }

    @Test
    void aliasRules() {
        assertThat(ModelPatterns.isAlias("devstral-medium-latest")).isTrue();
        assertThat(ModelPatterns.isAlias("claude-sonnet-4-5")).isTrue();
        assertThat(ModelPatterns.isAlias("claude-sonnet-4-5-20250929")).isFalse();
    }

    // ── parse：后缀递归（pi parseModelPattern 案例） ─────────────────────

    @Test
    void parseExactWithoutSuffix() {
        var r = ModelPatterns.parse("claude-sonnet-4-5", MODELS, false);
        assertThat(r.model().orElseThrow().id().modelName()).isEqualTo("claude-sonnet-4-5");
        assertThat(r.level()).isEmpty();
        assertThat(r.warning()).isEmpty();
    }

    @Test
    void parseValidThinkingLevels() {
        for (var level : List.of("off", "minimal", "low", "medium", "high", "xhigh", "max")) {
            var r = ModelPatterns.parse("sonnet:" + level, MODELS, false);
            assertThat(r.model()).isPresent();
            assertThat(r.level()).contains(level);
            assertThat(r.warning()).isEmpty();
        }
        // 大小写敏感（pi args.ts:61-65）。
        var r = ModelPatterns.parse("sonnet:HIGH", MODELS, false);
        assertThat(r.warning()).isPresent();
        assertThat(r.level()).isEmpty();
    }

    @Test
    void parseInvalidSuffixWarnsInScopeMode() {
        var r = ModelPatterns.parse("sonnet:random", MODELS, false);
        assertThat(r.model().orElseThrow().id().modelName()).isEqualTo("claude-sonnet-4-5");
        assertThat(r.level()).isEmpty();
        assertThat(r.warning()).contains(
            "Invalid thinking level \"random\" in pattern \"sonnet:random\". Using default instead.");
    }

    @Test
    void parseInvalidSuffixStaysPartOfIdInStrictMode() {
        // 即使前缀能匹配，严格模式也不切（pi：不静默切到别的模型）。
        assertThat(ModelPatterns.parse("sonnet:random", MODELS, true).model()).isEmpty();
        assertThat(ModelPatterns.parse("sonnet:random", MODELS, true).warning()).isEmpty();
    }

    @Test
    void parseColonIdExactFirst() {
        var r = ModelPatterns.parse("openrouter/qwen/qwen3-coder:exacto", MODELS, false);
        assertThat(r.model().orElseThrow().id())
            .isEqualTo(ModelId.of("openrouter", "qwen/qwen3-coder:exacto"));
        assertThat(r.level()).isEmpty();
    }

    @Test
    void parseColonIdWithLevel() {
        var r = ModelPatterns.parse("openrouter/qwen/qwen3-coder:exacto:high", MODELS, false);
        assertThat(r.model().orElseThrow().id().modelName()).isEqualTo("qwen/qwen3-coder:exacto");
        assertThat(r.level()).contains("high");
    }

    @Test
    void innerWarningDropsOuterLevel() {
        var r = ModelPatterns.parse("qwen/qwen3-coder:exacto:high:random", MODELS, false);
        assertThat(r.model().orElseThrow().id().modelName()).isEqualTo("qwen/qwen3-coder:exacto");
        assertThat(r.level()).isEmpty();
        assertThat(r.warning()).hasValueSatisfying(w -> assertThat(w).contains("random"));
    }

    @Test
    void parseTrailingColonWarns() {
        var r = ModelPatterns.parse("sonnet:", MODELS, false);
        assertThat(r.model().orElseThrow().id().modelName()).isEqualTo("claude-sonnet-4-5");
        assertThat(r.warning()).isPresent();
    }

    @Test
    void parseEmptyPatternSubstringMatchesAll() {
        // pi 边缘案例：空串是所有 id 的子串。
        assertThat(ModelPatterns.parse("", MODELS, false).model()).isPresent();
    }
}
