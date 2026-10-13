package com.pijava.ai.provider;

import java.util.LinkedHashMap;
import java.util.List;

import com.pijava.ai.catalog.InputLimits;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.provider.ModelsJsonSchema.Cost;
import com.pijava.ai.provider.ModelsJsonSchema.ImageLimitsDef;
import com.pijava.ai.provider.ModelsJsonSchema.InputLimitsDef;
import com.pijava.ai.provider.ModelsJsonSchema.ModelDef;
import com.pijava.ai.provider.ModelsJsonSchema.ModelOverrideDef;
import com.pijava.ai.provider.ModelsJsonSchema.ProviderDef;
import com.pijava.ai.provider.ModelsJsonSchema.ResizeDef;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 3a T4（#90）：{@code Model.inputLimits} 三层嵌套从 models.json（模型/覆盖两层）
 * 与 npm 生成分片到 {@link ModelInfo#inputLimits()} 的接通。消费链（图片缩放，B85）
 * 不在本批范围 —— 这里只钉解析与深合并。
 */
class ModelInputLimitsWireTest {

    private static ProviderDef provider() {
        return new ProviderDef(null, "https://example.test", null, "openai-responses",
            null, null, null, null);
    }

    private static ModelDef modelWith(InputLimitsDef limits) {
        return new ModelDef("m1", "M1", "openai-responses", "https://example.test",
            true, List.of("text", "image"), new Cost(1.0, 2.0, 0.0, 0.0, null),
            100_000, 16_384, null, null, null, null, null, limits, null);
    }

    @Test
    void allFourResizeFieldsAndByteImageCapsParse() {
        var limits = new InputLimitsDef(33_554_432,
            new ImageLimitsDef(new ResizeDef(2000, 2000, 4_718_592, 80), null, 600));

        var info = ModelsJsonConfig.toModelInfo("anthropic", provider(), modelWith(limits));

        var parsed = info.inputLimits();
        assertThat(parsed.maxRequestBytes()).isEqualTo(33_554_432);
        assertThat(parsed.images().maxPerRequest()).isEqualTo(600);
        assertThat(parsed.images().maxPerMessage()).isNull();
        var resize = parsed.images().resize();
        assertThat(resize.maxWidth()).isEqualTo(2000);
        assertThat(resize.maxHeight()).isEqualTo(2000);
        assertThat(resize.maxBytes()).isEqualTo(4_718_592);
        assertThat(resize.jpegQuality()).isEqualTo(80);
    }

    @Test
    void resizeOnlyShapeDoesNotFabricateEmptyShells() {
        // 13 家长尾的形状：只有 images.resize，maxRequestBytes/maxPerRequest 缺席。
        var limits = new InputLimitsDef(null,
            new ImageLimitsDef(new ResizeDef(2000, 2000, 4_718_592, 80), null, null));

        var info = ModelsJsonConfig.toModelInfo("mistral", provider(), modelWith(limits));

        var parsed = info.inputLimits();
        assertThat(parsed.maxRequestBytes()).isNull();
        assertThat(parsed.images().maxPerRequest()).isNull();
        assertThat(parsed.images().maxPerMessage()).isNull();
        assertThat(parsed.images().resize().maxWidth()).isEqualTo(2000);
    }

    @Test
    void absentAndAllNullStayNull() {
        assertThat(ModelsJsonConfig.toModelInfo("openai", provider(), modelWith(null))
            .inputLimits()).isNull();
        var allNull = ModelsJsonConfig.toModelInfo("openai", provider(),
            modelWith(new InputLimitsDef(null, new ImageLimitsDef(null, null, null))));
        assertThat(allNull.inputLimits()).isNull();
    }

    @Test
    void overrideDeepMergesAllThreeLayers() {
        // base：字节限 32MB ＋ 600 张 ＋ jpeg 80；override：只给 100 张。
        var baseDef = new InputLimitsDef(33_554_432,
            new ImageLimitsDef(new ResizeDef(2000, 2000, 4_718_592, 80), null, 600));
        var baseModel = ModelsJsonConfig.toModelInfo("anthropic", provider(), modelWith(baseDef));
        var overrides = new LinkedHashMap<String, ModelOverrideDef>();
        overrides.put("m1", new ModelOverrideDef(null, null, null, null, null, null,
            null, null, null, null, null,
            new InputLimitsDef(null, new ImageLimitsDef(null, null, 100)), null));
        var cfg = new ProviderDef(null, null, null, null, null, null, overrides, null);

        var merged = ModelJsonMerge.merge("anthropic", List.of(baseModel), cfg).getFirst()
            .inputLimits();

        // 标量保留（pi {...base, ...override}）。
        assertThat(merged.maxRequestBytes()).isEqualTo(33_554_432);
        // images 深一层：maxPerRequest 覆盖，maxPerMessage 仍缺席。
        assertThat(merged.images().maxPerRequest()).isEqualTo(100);
        assertThat(merged.images().maxPerMessage()).isNull();
        // resize 再深一层：override 没碰 ⇒ base 的 jpegQuality 存活。
        assertThat(merged.images().resize().jpegQuality()).isEqualTo(80);
        assertThat(merged.images().resize().maxBytes()).isEqualTo(4_718_592);
    }

    @Test
    void overrideResizeFieldMergesWithoutDroppingSiblings() {
        var baseDef = new InputLimitsDef(null,
            new ImageLimitsDef(new ResizeDef(2000, 2000, 4_718_592, 80), null, null));
        var baseModel = ModelsJsonConfig.toModelInfo("mistral", provider(), modelWith(baseDef));
        var overrides = new LinkedHashMap<String, ModelOverrideDef>();
        overrides.put("m1", new ModelOverrideDef(null, null, null, null, null, null,
            null, null, null, null, null,
            new InputLimitsDef(null,
                new ImageLimitsDef(new ResizeDef(1024, null, null, null), null, null)),
            null));
        var cfg = new ProviderDef(null, null, null, null, null, null, overrides, null);

        var resize = ModelJsonMerge.merge("mistral", List.of(baseModel), cfg).getFirst()
            .inputLimits().images().resize();
        assertThat(resize.maxWidth()).isEqualTo(1024);
        assertThat(resize.maxHeight()).isEqualTo(2000);
        assertThat(resize.maxBytes()).isEqualTo(4_718_592);
        assertThat(resize.jpegQuality()).isEqualTo(80);
    }

    @Test
    void shardValuesArePinned() {
        // anthropic fable-5：32MB / 600 张 / 2000x2000 / 4MB / 80。
        var fable = BuiltinModelCatalogs.catalog("anthropic")
            .find(com.pijava.ai.model.ModelId.of("anthropic", "claude-fable-5")).orElseThrow()
            .inputLimits();
        assertThat(fable.maxRequestBytes()).isEqualTo(33_554_432);
        assertThat(fable.images().maxPerRequest()).isEqualTo(600);
        assertThat(fable.images().resize().maxBytes()).isEqualTo(4_718_592);

        // 100 张变体。
        var haiku = BuiltinModelCatalogs.catalog("anthropic")
            .find(com.pijava.ai.model.ModelId.of("anthropic", "claude-haiku-4-5")).orElseThrow()
            .inputLimits();
        assertThat(haiku.images().maxPerRequest()).isEqualTo(100);

        // 13 家长尾：mistral 仅 resize，无字节限（deepseek 整条缺席）。
        var mistral = BuiltinModelCatalogs.catalog("mistral")
            .find(com.pijava.ai.model.ModelId.of("mistral", "mistral-large-latest")).orElseThrow()
            .inputLimits();
        assertThat(mistral.maxRequestBytes()).isNull();
        assertThat(mistral.images().resize().jpegQuality()).isEqualTo(80);
        var deepseek = BuiltinModelCatalogs.catalog("deepseek")
            .find(com.pijava.ai.model.ModelId.of("deepseek", "deepseek-v4-pro")).orElseThrow();
        assertThat(deepseek.inputLimits()).isNull();
    }
}
