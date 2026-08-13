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
    void resolvePatternThrowsOnUnknownModel() {
        var catalog = catalog(List.of(
                info("anthropic", "claude-sonnet", Set.of(ModelCapability.TEXT))));
        var resolver = new DefaultModelResolver(catalog);

        assertThatThrownBy(() -> resolver.resolve("anthropic/does-not-exist"))
                .isInstanceOf(IllegalStateException.class);
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
