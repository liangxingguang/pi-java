package com.pijava.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.ModelsJsonSchema.ModelDef;
import com.pijava.ai.provider.ModelsJsonSchema.ModelOverrideDef;
import com.pijava.ai.provider.ModelsJsonSchema.ProviderDef;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * D-P1（{@code 原 docs/65}）：models.json 配置对内置 provider 的分层合并哨兵
 * （pi {@code provider-composer.ts:184-221}）。
 */
class ModelJsonMergeTest {

    private static ModelInfo base(String id) {
        return base(id, null, Map.of());
    }

    private static ModelInfo base(String id, String api, Map<String, String> headers) {
        return new ModelInfo(ModelId.of("openai", id), id,
            java.util.Set.of(), 100_000, 16_384, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), headers, Map.of(), ModelCompat.NONE, api);
    }

    private static ProviderDef cfg(String baseUrl, String api, List<ModelDef> models,
                                   Map<String, String> headers,
                                   Map<String, ModelOverrideDef> overrides) {
        return new ProviderDef(null, baseUrl, null, api, models, headers, overrides, null);
    }

    private static ModelDef modelDef(String id, String api, String baseUrl,
                                     Map<String, String> headers) {
        return new ModelDef(id, null, api, baseUrl, null, null, null, null, null,
            headers, null, null, null);
    }

    // ── provider 级覆盖 ─────────────────────────────────────────────

    @Test
    void providerBaseUrlOverlaysEveryBaseModel() {
        var merged = ModelJsonMerge.merge("openai",
            List.of(base("gpt-a"), base("gpt-b")),
            cfg("https://relay/v1", null, null, null, null));

        assertThat(merged).hasSize(2);
        assertThat(merged).allSatisfy(m -> {
            assertThat(m.api()).as("api 未写时继承内置模型原值").isNull();
            assertThat(m.baseUrl()).isEqualTo("https://relay/v1");
        });
    }

    @Test
    void modelsUpsertReplacesSameIdAndKeepsOthers() {
        var merged = ModelJsonMerge.merge("openai",
            List.of(base("gpt-a"), base("gpt-b")),
            cfg(null, null,
                List.of(modelDef("gpt-a", "openai-completions", null, null)),
                null, null));

        assertThat(merged).hasSize(2);
        var replaced = merged.stream().filter(m -> m.id().modelName().equals("gpt-a"))
            .findFirst().orElseThrow();
        assertThat(replaced.api()).isEqualTo("openai-completions");
        var untouched = merged.stream().filter(m -> m.id().modelName().equals("gpt-b"))
            .findFirst().orElseThrow();
        assertThat(untouched.api()).isNull();
    }

    @Test
    void perModelBaseUrlWinsOverProviderAndBase() {
        var merged = ModelJsonMerge.merge("openai",
            List.of(base("gpt-a")),
            cfg("https://provider-url", null,
                List.of(modelDef("gpt-a", null, "https://model-url", null)),
                null, null));

        // 三源：model.baseUrl ?? provider.baseUrl ?? base —— model 级最高（B136）。
        assertThat(merged.get(0).baseUrl()).isEqualTo("https://model-url");
        var onlyProvider = ModelJsonMerge.merge("openai",
            List.of(base("gpt-b")),
            cfg("https://provider-url", null, null, null, null));
        assertThat(onlyProvider.get(0).baseUrl()).isEqualTo("https://provider-url");
    }

    // ── headers 三层 ────────────────────────────────────────────────

    @Test
    void headersMergeBaseOverrideModelInOrder() {
        var override = new LinkedHashMap<String, ModelOverrideDef>();
        override.put("gpt-a", new ModelOverrideDef(null, null, null, null, null, null,
            null, null, null, Map.of("X-Override", "o", "X-Shared", "from-override"), null));
        var merged = ModelJsonMerge.merge("openai",
            List.of(base("gpt-a", null, Map.of("X-Base", "b", "X-Shared", "from-base"))),
            cfg(null, null,
                List.of(modelDef("gpt-a", null, null,
                    Map.of("X-Model", "m", "X-Shared", "from-model"))),
                null, override));

        var headers = merged.get(0).headers();
        assertThat(headers).containsEntry("X-Base", "b")
            .containsEntry("X-Override", "o")
            .containsEntry("X-Model", "m")
            // 次序：base → modelOverrides → models[]（models[] 最后，同键覆盖）。
            .containsEntry("X-Shared", "from-model");
    }

    @Test
    void providerHeadersApplyToEveryModel() {
        var merged = ModelJsonMerge.merge("openai",
            List.of(base("gpt-a"), base("gpt-b")),
            cfg(null, null, null, Map.of("X-Test", "t"), null));

        assertThat(merged).allSatisfy(m ->
            assertThat(m.headers()).containsEntry("X-Test", "t"));
    }

    // ── modelOverrides（最顶层）──────────────────────────────────────

    @Test
    void modelOverridesChangeOnlyNamedFields() {
        var override = new LinkedHashMap<String, ModelOverrideDef>();
        override.put("gpt-a", new ModelOverrideDef(null, null, null, null, null, null,
            200_000, null, null, null, null));
        var merged = ModelJsonMerge.merge("openai",
            List.of(base("gpt-a"), base("gpt-b")),
            cfg(null, null, null, null, override));

        assertThat(merged.get(0).maxInputTokens()).isEqualTo(200_000);
        assertThat(merged.get(0).maxOutputTokens()).isEqualTo(16_384);
        assertThat(merged.get(1).maxInputTokens()).isEqualTo(100_000);
    }

    // ── api 三源（R1）────────────────────────────────────────────────

    @Test
    void replacementWithoutApiInheritsTheBaseModelApi() {
        // R1：替换内置模型、不给 api ⇒ 继承被替换项的 api（不抛错）。
        var merged = ModelJsonMerge.merge("openai",
            List.of(base("gpt-a", "openai-responses", Map.of())),
            cfg(null, null,
                List.of(modelDef("gpt-a", null, null, null)),
                null, null));

        assertThat(merged.get(0).api()).isEqualTo("openai-responses");
        // provider 级 api 次之。
        var viaProvider = ModelJsonMerge.merge("openai",
            List.of(base("gpt-c")),
            cfg(null, "openai-responses",
                List.of(modelDef("gpt-c", null, null, null)),
                null, null));
        assertThat(viaProvider.get(0).api()).isEqualTo("openai-responses");
    }

    // ── 校验 ────────────────────────────────────────────────────────

    @Test
    void emptyProviderEntryThrows() {
        assertThatThrownBy(() -> ModelJsonMerge.merge("openai",
            List.of(base("gpt-a")),
            cfg(null, null, null, null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openai");
    }

    @Test
    void headersOnlyEntryIsAccepted() {
        var merged = ModelJsonMerge.merge("openai",
            List.of(base("gpt-a")),
            cfg(null, null, null, Map.of("X-Test", "t"), null));

        assertThat(merged.get(0).headers()).containsEntry("X-Test", "t");
    }
}
