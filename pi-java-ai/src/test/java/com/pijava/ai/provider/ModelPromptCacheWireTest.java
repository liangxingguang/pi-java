package com.pijava.ai.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ModelsJsonSchema.Cost;
import com.pijava.ai.provider.ModelsJsonSchema.ModelDef;
import com.pijava.ai.provider.ModelsJsonSchema.ModelOverrideDef;
import com.pijava.ai.provider.ModelsJsonSchema.ProviderDef;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 3a T3（#86）：{@code Model.promptCache} 从 models.json（模型/覆盖两层）与 npm
 * 生成分片到 {@link ModelInfo#promptCache()} 的接通。pi schema：
 * {@code Partial<Record<"short"|"long", number>>}，秒数，exclusiveMinimum 0。
 */
class ModelPromptCacheWireTest {

    private static ProviderDef provider() {
        return new ProviderDef(null, "https://example.test", null, "openai-responses",
            null, null, null, null);
    }

    private static ModelDef modelWithPromptCache(Map<String, Double> promptCache) {
        return new ModelDef("m1", "M1", "openai-responses", "https://example.test",
            true, List.of("text"), new Cost(1.0, 2.0, 0.0, 0.0, null),
            100_000, 16_384, null, null, null, null,
            promptCache, null, null);
    }

    @Test
    void modelsJsonModelCarriesBothRetentions() {
        var info = ModelsJsonConfig.toModelInfo("openai", provider(),
            modelWithPromptCache(Map.of("short", 300.0, "long", 3600.0)));

        assertThat(info.promptCache()).isNotNull();
        assertThat(info.promptCache().shortSeconds()).isEqualTo(300);
        assertThat(info.promptCache().longSeconds()).isEqualTo(3600);
    }

    @Test
    void unknownKeysZeroAndNegativeRetentionsAreIgnored() {
        // 宽松加载不炸文件（≙ pi schema 校验失败的安全方向），坏档归一未知。
        var wire = new LinkedHashMap<String, Double>();
        wire.put("short", 0.0);
        wire.put("long", -5.0);
        wire.put("medium", 900.0);
        assertThat(ModelsJsonConfig.toModelInfo("openai", provider(), modelWithPromptCache(wire))
            .promptCache()).isNull();

        var onlyShort = new LinkedHashMap<String, Double>();
        onlyShort.put("short", 300.0);
        onlyShort.put("long", -1.0);
        var partial = ModelsJsonConfig.toModelInfo("openai", provider(),
            modelWithPromptCache(onlyShort)).promptCache();
        assertThat(partial.shortSeconds()).isEqualTo(300);
        assertThat(partial.longSeconds()).isNull();
    }

    @Test
    void overrideMergesRetentionKeysWithoutDroppingTheOtherTier() {
        // models[] 基模型给 short，modelOverrides 只给 long ⇒ 两档都在（键级合并）。
        var baseModel = ModelsJsonConfig.toModelInfo("openai", provider(),
            modelWithPromptCache(Map.of("short", 300.0)));
        var overrides = new LinkedHashMap<String, ModelOverrideDef>();
        overrides.put("m1", new ModelOverrideDef(null, null, null, null, null,
            Map.of("long", 3600.0), null, null, null, null, null, null, null));
        var cfg = new ProviderDef(null, null, null, null, null, null, overrides, null);

        var merged = ModelJsonMerge.merge("openai", List.of(baseModel), cfg);

        var cache = merged.getFirst().promptCache();
        assertThat(cache.shortSeconds()).isEqualTo(300);
        assertThat(cache.longSeconds()).isEqualTo(3600);
    }

    @Test
    void absentFieldStaysAbsent() {
        var info = ModelsJsonConfig.toModelInfo("openai", provider(),
            modelWithPromptCache(null));
        assertThat(info.promptCache()).isNull();
    }

    @Test
    void everyAnthropicShardEntryCarriesThePinnedLifetimes() {
        // 17 条 anthropic 模型全部 {short:300,long:3600}（npm 1.1.0 实测）。
        var anthropic = BuiltinModelCatalogs.catalog("anthropic");
        assertThat(anthropic.listModels()).isNotEmpty();
        assertThat(anthropic.listModels()).allSatisfy(m -> {
            assertThat(m.promptCache()).isNotNull();
            assertThat(m.promptCache().shortSeconds()).isEqualTo(300);
            assertThat(m.promptCache().longSeconds()).isEqualTo(3600);
        });
        // 非 anthropic 家不携带该字段（生成数据零生产者）。
        assertThat(BuiltinModelCatalogs.catalog("openai").listModels())
            .allSatisfy(m -> assertThat(m.promptCache()).isNull());
        // 自识别：模型 id 不应落进错误目录（防 find 误判）。
        assertThat(anthropic.find(ModelId.of("anthropic", "claude-fable-5"))).isPresent();
    }
}
