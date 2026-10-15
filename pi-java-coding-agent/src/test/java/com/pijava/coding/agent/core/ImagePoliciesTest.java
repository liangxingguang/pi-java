package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pijava.ai.catalog.InputLimits;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.PromptCacheLifetimes;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * 批 9/B85：{@link ImagePolicies} 对拍 pi 的
 * {@code settingsManager.getImageAutoResize() ?? true} ＋ {@code _limitsModel()} 档位/视觉位。
 * 直调静态解析，不起整会话（会话晚绑定接线在 agent-core PiToolRunner 夹具覆盖）。
 */
class ImagePoliciesTest {

    private static final ModelId<?> ID = ModelId.of("test", "vision-model");

    private static ModelCatalog catalogOf(ModelInfo info) {
        return new ModelCatalog() {
            @Override public List<ModelInfo> listModels() { return List.of(info); }
            @Override public Optional<ModelInfo> find(ModelId<?> id) {
                return id.equals(ID) ? Optional.of(info) : Optional.empty();
            }
            @Override public List<ModelInfo> search(String query) { return List.of(); }
        };
    }

    private static ModelInfo model(Set<ModelCapability> capabilities, InputLimits limits) {
        return new ModelInfo(ID, "Vision model", capabilities, 100_000, 4096,
            false, null, ThinkingLevelMap.empty(), Map.of(), Map.of(),
            ModelCompat.NONE, null, null, (PromptCacheLifetimes) null, limits, Map.of());
    }

    private static Settings settings(Boolean autoResize) {
        var settings = new Settings();
        settings.images = autoResize == null ? null : new Settings.Image(autoResize, null);
        return settings;
    }

    @Test
    void defaultSettingsOpenTheGate() {
        var catalog = catalogOf(model(Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT), null));
        var policy = ImagePolicies.resolve(settings(null), catalog, ID);
        assertThat(policy.autoResize()).isTrue();
        assertThat(policy.supportsImages()).isTrue();
        assertThat(policy.resize()).isNull();
    }

    @Test
    void settingsAutoResizeFalseClosesTheGate() {
        var catalog = catalogOf(model(Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT), null));
        var policy = ImagePolicies.resolve(settings(false), catalog, ID);
        assertThat(policy.autoResize()).isFalse();
    }

    @Test
    void modelResizeProfileAndVisionBitFlowThrough() {
        var resize = new InputLimits.ResizeOptions(1234, 1000, 500_000, 70);
        var limits = new InputLimits(null, new InputLimits.ImageLimits(resize, null, null));
        var catalog = catalogOf(
            model(Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT), limits));
        var policy = ImagePolicies.resolve(settings(true), catalog, ID);
        assertThat(policy.resize()).isEqualTo(resize);
        assertThat(policy.supportsImages()).isTrue();
    }

    @Test
    void textOnlyModelProducesNonVisionPolicy() {
        var catalog = catalogOf(model(Set.of(ModelCapability.TEXT), null));
        var policy = ImagePolicies.resolve(settings(null), catalog, ID);
        assertThat(policy.supportsImages()).isFalse();
    }

    @Test
    void unknownModelDefaultsToSupportedAndNoProfile() {
        // 「未知按支持」是 Java 三态的既有裁决（响亮优于静默），与 pi 注记只在模型确知时出现同效。
        var catalog = catalogOf(model(Set.of(ModelCapability.TEXT), null));
        var policy = ImagePolicies.resolve(settings(null), catalog,
            ModelId.of("test", "mystery"));
        assertThat(policy.supportsImages()).isTrue();
        assertThat(policy.resize()).isNull();
    }
}
