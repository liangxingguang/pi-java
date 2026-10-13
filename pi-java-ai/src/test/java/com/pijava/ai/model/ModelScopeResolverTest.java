package com.pijava.ai.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 移植 pi {@code test/model-resolver.test.ts:223-319} 的
 * resolveModelScopeWithDiagnostics 夹具（mock 模型形状照 pi 文件头）。
 */
class ModelScopeResolverTest {

    private static ModelInfo info(String provider, String id, String name) {
        return new ModelInfo(ModelId.of(provider, id), name, Set.of(ModelCapability.TEXT),
            128_000, 4096, false, PricingInfo.UNKNOWN);
    }

    private static final ModelInfo SONNET =
        info("anthropic", "claude-sonnet-4-5", "Claude Sonnet 4.5");
    private static final ModelInfo GPT_4O = info("openai", "gpt-4o", "GPT-4o");
    private static final ModelInfo QWEN_EXACTO =
        info("openrouter", "qwen/qwen3-coder:exacto", "Qwen3 Coder Exacto");
    private static final ModelInfo GPT_EXTENDED =
        info("openrouter", "openai/gpt-4o:extended", "GPT-4o Extended");

    private static final List<ModelInfo> ALL = List.of(SONNET, GPT_4O, QWEN_EXACTO, GPT_EXTENDED);

    @Test
    void mixedPatternsResolveInOrderWithLevelsAndDiagnostics() {
        var result = ModelScopeResolver.resolve(
            List.of("sonnet:high", "gpt-4o:invalid", "missing"), ALL);

        assertThat(result.scopedModels().stream().map(sm -> sm.model().id()).toList())
            .isEqualTo(List.of(SONNET.id(), GPT_4O.id()));
        assertThat(result.scopedModels().get(0).thinkingLevel()).contains("high");
        assertThat(result.scopedModels().get(1).thinkingLevel()).isEmpty();
        assertThat(result.diagnostics()).hasSize(2);
        assertThat(result.diagnostics().get(0).code())
            .isEqualTo(ModelScopeDiagnostic.Code.INVALID_THINKING_LEVEL);
        assertThat(result.diagnostics().get(0).message())
            .isEqualTo("Invalid thinking level \"invalid\" in pattern \"gpt-4o:invalid\"."
                + " Using default instead.");
        assertThat(result.diagnostics().get(0).pattern()).isEqualTo("gpt-4o:invalid");
        assertThat(result.diagnostics().get(1).code())
            .isEqualTo(ModelScopeDiagnostic.Code.NO_MATCH);
        assertThat(result.diagnostics().get(1).message())
            .isEqualTo("No models match pattern \"missing\"");
    }

    @Test
    void bracketedIdIsExactReferenceBeforeGlob() {
        var models = new ArrayList<>(ALL);
        models.add(info("custom", "bracketed-model[1m]", "Bracketed Model"));

        var result = ModelScopeResolver.resolve(
            List.of("custom/bracketed-model[1m]"), models);

        assertThat(result.scopedModels()).hasSize(1);
        assertThat(result.scopedModels().getFirst().model().id().modelName())
            .isEqualTo("bracketed-model[1m]");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void bracketedIdWithLevelIsExactReferenceBeforeGlob() {
        var models = new ArrayList<>(ALL);
        models.add(info("custom", "bracketed-model[1m]", "Bracketed Model"));

        var result = ModelScopeResolver.resolve(
            List.of("custom/bracketed-model[1m]:high"), models);

        assertThat(result.scopedModels()).hasSize(1);
        assertThat(result.scopedModels().getFirst().thinkingLevel()).contains("high");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void globExpandsProviderLane() {
        var result = ModelScopeResolver.resolve(List.of("anthropic/*"), ALL);
        assertThat(result.scopedModels().stream().map(sm -> sm.model().id()).toList())
            .isEqualTo(List.of(SONNET.id()));
    }

    @Test
    void globWorksOnBareIdLane() {
        // "*sonnet*" 不必写 "anthropic/*sonnet*"（pi :312-316 双车道）。
        var result = ModelScopeResolver.resolve(List.of("*sonnet*"), ALL);
        assertThat(result.scopedModels()).extracting(sm -> sm.model().id().modelName())
            .containsExactly("claude-sonnet-4-5");
    }

    @Test
    void globStarDoesNotCrossSlash() {
        // openrouter 两条 id 自带斜杠："openrouter/*" 的单段星不吃斜杠，均不命中。
        var result = ModelScopeResolver.resolve(List.of("openrouter/*"), ALL);
        assertThat(result.scopedModels()).isEmpty();
        assertThat(result.diagnostics().getFirst().code())
            .isEqualTo(ModelScopeDiagnostic.Code.NO_MATCH);
    }

    @Test
    void globstarReachesSlashedIds() {
        var result = ModelScopeResolver.resolve(List.of("openrouter/**"), ALL);
        assertThat(result.scopedModels().stream().map(sm -> sm.model().id()).toList())
            .isEqualTo(List.of(QWEN_EXACTO.id(), GPT_EXTENDED.id()));
    }

    @Test
    void globLevelSuffixAppliesToAllMatches() {
        var result = ModelScopeResolver.resolve(List.of("anthropic/*:max"), ALL);
        assertThat(result.scopedModels()).hasSize(1);
        assertThat(result.scopedModels().getFirst().thinkingLevel()).contains("max");
    }

    @Test
    void noMatchDiagnosticUsesOriginalPattern() {
        var result = ModelScopeResolver.resolve(List.of("anthropic/*:bogus"), ALL);
        // 非法后缀不剥 ⇒ glob 字面匹配失败 ⇒ no-match，pattern 是原始串。
        assertThat(result.scopedModels()).isEmpty();
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().getFirst().pattern())
            .isEqualTo("anthropic/*:bogus");
    }

    @Test
    void duplicatesAcrossPatternsAreRemoved() {
        var result = ModelScopeResolver.resolve(
            List.of("sonnet", "anthropic/claude-sonnet-4-5"), ALL);
        assertThat(result.scopedModels()).hasSize(1);
        assertThat(result.scopedModels().getFirst().model().id()).isEqualTo(SONNET.id());
    }

    @Test
    void emptyPatternsGiveEmptyResult() {
        var result = ModelScopeResolver.resolve(List.of(), ALL);
        assertThat(result.scopedModels()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void questionMarkIsSingleCharacter() {
        var result = ModelScopeResolver.resolve(List.of("gpt-4?"), ALL);
        assertThat(result.scopedModels()).extracting(sm -> sm.model().id().modelName())
            .containsExactly("gpt-4o");
    }
}
