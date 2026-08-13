package com.pijava.ai.catalog;

import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BuiltinCatalog} — the built-in model directory.
 */
class BuiltinCatalogTest {

    @Test
    void shouldContainAnthropicModels() {
        var catalog = BuiltinCatalog.anthropicModels();
        var models = catalog.listModels();

        assertThat(models).isNotEmpty();
        assertThat(models).anyMatch(m -> m.id().modelName().contains("claude"));
    }

    @Test
    void shouldContainOpenAIModels() {
        var catalog = BuiltinCatalog.openaiModels();
        var models = catalog.listModels();

        assertThat(models).isNotEmpty();
        assertThat(models).anyMatch(m -> m.id().modelName().contains("gpt"));
    }

    @Test
    void shouldContainGoogleModels() {
        var catalog = BuiltinCatalog.googleModels();
        var models = catalog.listModels();

        assertThat(models).isNotEmpty();
        assertThat(models).anyMatch(m -> m.id().modelName().contains("gemini"));
    }

    @Test
    void shouldContainDeepseekModels() {
        var catalog = BuiltinCatalog.deepseekModels();
        var models = catalog.listModels();

        assertThat(models).isNotEmpty();
        assertThat(models).anyMatch(m -> m.id().modelName().contains("deepseek"));
    }

    @Test
    void shouldContainMistralModels() {
        var catalog = BuiltinCatalog.mistralModels();
        var models = catalog.listModels();

        assertThat(models).isNotEmpty();
        assertThat(models).anyMatch(m -> m.id().modelName().contains("mistral"));
    }

    @Test
    void searchShouldBeCaseInsensitive() {
        var catalog = BuiltinCatalog.anthropicModels();

        var results = catalog.search("CLAUDE");
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(m -> m.id().provider().equals("anthropic"));
    }

    @Test
    void searchShouldDoSubstringMatching() {
        var catalog = BuiltinCatalog.anthropicModels();

        var results = catalog.search("sonnet");
        assertThat(results).isNotEmpty();
    }

    @Test
    void searchShouldReturnEmptyForNoMatch() {
        var catalog = BuiltinCatalog.anthropicModels();
        var results = catalog.search("nonexistent-model-xyz");
        assertThat(results).isEmpty();
    }

    @Test
    void shouldFindModelById() {
        var catalog = BuiltinCatalog.anthropicModels();
        var models = catalog.listModels();
        var firstModel = models.get(0);

        var found = catalog.find(firstModel.id());
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(firstModel.id());
    }

    @Test
    void shouldReturnEmptyForUnknownModelId() {
        var catalog = BuiltinCatalog.anthropicModels();
        var unknown = ModelId.of("unknown-provider", "unknown-model");
        assertThat(catalog.find(unknown)).isEmpty();
    }

    @Test
    void modelInfoShouldHavePricing() {
        var catalog = BuiltinCatalog.anthropicModels();
        var models = catalog.listModels();

        assertThat(models).allMatch(m -> m.pricing().inputPrice() > 0);
        assertThat(models).allMatch(m -> m.pricing().outputPrice() > 0);
    }

    @Test
    void modelInfoShouldHaveCapabilities() {
        var catalog = BuiltinCatalog.anthropicModels();
        var models = catalog.listModels();

        assertThat(models).allMatch(m -> !m.capabilities().isEmpty());
    }

    @Test
    void emptyCatalogShouldReturnEmpty() {
        var catalog = ModelCatalog.empty();
        assertThat(catalog.listModels()).isEmpty();
        assertThat(catalog.search("anything")).isEmpty();
        assertThat(catalog.find(ModelId.of("x", "y"))).isEmpty();
    }

    @Test
    void allAggregatesEveryProvider() {
        var models = BuiltinCatalog.all().listModels();

        assertThat(models)
            .extracting(m -> m.id().provider())
            .contains("anthropic", "openai", "google", "deepseek", "mistral");
        assertThat(models.size()).isEqualTo(
            BuiltinCatalog.anthropicModels().listModels().size()
                + BuiltinCatalog.openaiModels().listModels().size()
                + BuiltinCatalog.googleModels().listModels().size()
                + BuiltinCatalog.deepseekModels().listModels().size()
                + BuiltinCatalog.mistralModels().listModels().size());
    }
}
