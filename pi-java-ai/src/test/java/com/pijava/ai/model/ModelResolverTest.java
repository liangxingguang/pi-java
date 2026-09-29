package com.pijava.ai.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelResolverTest {

    private static ModelInfo info(String provider, String model, Set<ModelCapability> caps) {
        return new ModelInfo(ModelId.of(provider, model), model, caps,
                100_000, 4000, false, PricingInfo.UNKNOWN);
    }

    private static ModelCatalog catalog(List<ModelInfo> models) {
        return new ModelCatalog() {
            @Override public List<ModelInfo> listModels() { return models; }
            @Override public Optional<ModelInfo> find(ModelId<?> id) {
                return models.stream().filter(m -> m.id().equals(id)).findFirst();
            }
            @Override public List<ModelInfo> search(String query) { return List.of(); }
        };
    }

    @Test
    void resolveReturnsCapabilityMatch() {
        var catalog = catalog(List.of(
                info("anthropic", "claude", Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE)),
                info("openai", "gpt", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        var resolved = resolver.resolve(Set.of(ModelCapability.TOOL_USE), Optional.empty());
        assertThat(resolved.modelName()).isEqualTo("claude");
    }

    @Test
    void resolvePrefersRequestedProvider() {
        var catalog = catalog(List.of(
                info("anthropic", "claude", Set.of(ModelCapability.TEXT)),
                info("openai", "gpt", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        var resolved = resolver.resolve(Set.of(ModelCapability.TEXT), Optional.of("openai"));
        assertThat(resolved.provider()).isEqualTo("openai");
    }

    @Test
    void resolveThrowsWhenNoMatch() {
        var catalog = catalog(List.of(
                info("anthropic", "claude", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        assertThatThrownBy(() -> resolver.resolve(Set.of(ModelCapability.IMAGE_INPUT), Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolvePatternWithProviderAndName() {
        var catalog = catalog(List.of(
                info("anthropic", "claude-sonnet", Set.of(ModelCapability.TEXT)),
                info("openai", "gpt-5", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        var resolved = resolver.resolve("openai/gpt-5");
        assertThat(resolved.provider()).isEqualTo("openai");
        assertThat(resolved.modelName()).isEqualTo("gpt-5");
    }

    /**
     * 包 A-02（docs/59 §4.7）：pi {@code parseModelPattern:209-216} —— **精确匹配先行**。
     * OpenRouter 的 {@code :batch}/{@code :exacto} 一族 id 自带冒号；改前冒号切分先行，
     * 会把 batch id 静默解析成非 batch 模型（另一条车道、另一个价）。
     */
    @Test
    void resolveMatchesColonIdsExactlyBeforeSplittingTheThinkingSuffix() {
        var catalog = catalog(List.of(
                info("openrouter", "anthropic/claude-fable-5", Set.of(ModelCapability.TEXT)),
                info("openrouter", "anthropic/claude-fable-5:batch",
                        Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        assertThat(resolver.resolve("openrouter/anthropic/claude-fable-5:batch").modelName())
                .isEqualTo("anthropic/claude-fable-5:batch");
        // 裸模型名同样精确匹配先行。
        assertThat(resolver.resolve("anthropic/claude-fable-5:batch").modelName())
                .isEqualTo("anthropic/claude-fable-5:batch");
        // 对照：真正的 thinking 后缀照旧被切掉（无精确命中才走到冒号切分）。
        assertThat(resolver.resolve("openrouter/anthropic/claude-fable-5:high").modelName())
                .isEqualTo("anthropic/claude-fable-5");
    }

    @Test
    void resolvePatternIgnoresThinkingSuffix() {
        var catalog = catalog(List.of(
                info("anthropic", "claude-sonnet", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        var resolved = resolver.resolve("claude-sonnet:high");
        assertThat(resolved.modelName()).isEqualTo("claude-sonnet");
    }

    @Test
    void resolvePatternWithBareProvider() {
        var catalog = catalog(List.of(
                info("google", "gemini-2.5-flash", Set.of(ModelCapability.TEXT)),
                info("google", "gemini-2.5-pro", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        var resolved = resolver.resolve("google");
        assertThat(resolved.provider()).isEqualTo("google");
    }

    @Test
    void resolvePatternAcceptsUnknownModel() {
        var catalog = catalog(List.of(
                info("anthropic", "claude-sonnet", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        var resolved = resolver.resolve("anthropic/does-not-exist", "anthropic");
        assertThat(resolved.provider()).isEqualTo("anthropic");
        assertThat(resolved.modelName()).isEqualTo("does-not-exist");
    }

    @Test
    void resolveBareUnknownModelUsesDefaultProvider() {
        var catalog = catalog(List.of(
                info("deepseek", "deepseek-chat", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        var resolved = resolver.resolve("deepseek-v4-flash", "deepseek");
        assertThat(resolved.provider()).isEqualTo("deepseek");
        assertThat(resolved.modelName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void resolveNullPatternFallsBackToDefaultProvider() {
        var catalog = catalog(List.of(
                info("google", "gemini-2.5-flash", Set.of(ModelCapability.TEXT)),
                info("openai", "gpt-5", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        var resolved = resolver.resolve((String) null);
        assertThat(resolved.provider()).isEqualTo("google");
    }
}
