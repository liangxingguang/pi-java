package com.pijava.ai.provider;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #33：图片目录来自 {@code openrouter.json} 的 {@code type:"image"} 条目，
 * 装载时把 provider 重映射成 Java 的 {@code openrouter-images}。
 */
class ImageModelCatalogTest {

    @Test
    void carriesEveryGeneratedOpenRouterImageModel() {
        var catalog = BuiltinModelCatalogs.imageCatalog("openrouter");

        assertThat(catalog.listModels()).hasSize(64);
        assertThat(catalog.listModels().getFirst().id().modelName())
            .isEqualTo("sourceful/riverflow-v2.6-pro");
        assertThat(catalog.search("flux.2-flex")).isNotEmpty();
    }

    @Test
    void remapsTheProviderToTheJavaImagesProvider() {
        var catalog = BuiltinModelCatalogs.imageCatalog("openrouter");
        var flux = catalog.find(ModelId.of("openrouter-images", "black-forest-labs/flux.2-flex"))
            .orElseThrow();

        assertThat(flux.displayName()).isEqualTo("Black Forest Labs: FLUX.2 Flex");
        assertThat(flux.api()).isNull();
        assertThat(flux.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(flux.capabilities()).contains(
            ModelCapability.IMAGE_INPUT, ModelCapability.IMAGE_OUTPUT);
        assertThat(flux.maxInputTokens()).isZero();
        assertThat(flux.maxOutputTokens()).isZero();
    }

    @Test
    void mapsPricingAndImageInputLimits() {
        var gemini = BuiltinModelCatalogs.imageCatalog("openrouter")
            .find(ModelId.of("openrouter-images", "google/gemini-nano-banana-2.1"))
            .orElseThrow();

        assertThat(gemini.pricing().inputPrice()).isEqualTo(1.5);
        assertThat(gemini.pricing().outputPrice()).isEqualTo(7.5);
        assertThat(gemini.inputLimits().images().resize().maxWidth()).isEqualTo(2000);
        assertThat(gemini.inputLimits().images().resize().maxHeight()).isEqualTo(2000);
        assertThat(gemini.inputLimits().images().resize().maxBytes()).isEqualTo(4_718_592);
        assertThat(gemini.inputLimits().images().resize().jpegQuality()).isEqualTo(80);
    }

    @Test
    void imageEntriesDoNotLeakIntoTheChatCatalog() {
        assertThat(BuiltinModelCatalogs.catalog("openrouter")
            .find(ModelId.of("openrouter", "black-forest-labs/flux.2-flex")))
            .isEmpty();
    }

    @Test
    void onlyOpenRouterHasImageEntries() {
        assertThatThrownBy(() -> BuiltinModelCatalogs.imageCatalog("anthropic"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no image catalog");
    }
}
