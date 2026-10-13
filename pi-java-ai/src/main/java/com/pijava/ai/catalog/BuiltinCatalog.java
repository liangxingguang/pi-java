package com.pijava.ai.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

/**
 * Map-backed {@link ModelCatalog} for built-in models.
 *
 * <p>批 3a 起，主流 17 provider 的 chat 目录来自 npm {@code @earendil-works/pi-ai@1.1.0}
 * 生成分片（见 {@code provider.BuiltinModelCatalogs}）；本类只保留 Map 支撑、
 * OpenRouter 图像模型（#33 R5 面维持手写）与各种装配链共用的 {@link #of} 工厂。</p>
 */
public final class BuiltinCatalog implements ModelCatalog {

    private final Map<String, ModelInfo> modelsById;

    private BuiltinCatalog(List<ModelInfo> models) {
        // LinkedHashMap：保插入序——npm 分片装载后 listModels() 的序必须等同 pi
        // Object.values(拍平表) 的文件序（默认模型/搜索序依赖它，批 3a T1）。
        this.modelsById = new LinkedHashMap<>();
        for (var m : models) {
            modelsById.put(m.id().provider() + "/" + m.id().modelName(), m);
        }
    }

    /** OpenRouter image generation models (P6-28，对齐 pi {@code image-models.generated.ts}). */
    public static ModelCatalog openRouterImageModels() {
        return new BuiltinCatalog(List.of(
            imageModel("black-forest-labs/flux.2-flex", "Black Forest Labs: FLUX.2 Flex"),
            imageModel("black-forest-labs/flux.2-klein-4b", "Black Forest Labs: FLUX.2 Klein 4B"),
            imageModel("black-forest-labs/flux.2-max", "Black Forest Labs: FLUX.2 Max"),
            imageModel("black-forest-labs/flux.2-pro", "Black Forest Labs: FLUX.2 Pro"),
            imageModel("bytedance-seed/seedream-4.5", "Bytedance Seed: Seedream 4.5"),
            imageModel("google/gemini-2.5-flash-image", "Google: Gemini 2.5 Flash Image"),
            imageModel("google/gemini-3-pro-image", "Google: Gemini 3 Pro Image"),
            imageModel("google/gemini-3-pro-image-preview", "Google: Gemini 3 Pro Image Preview")
        ));
    }

    /** Build a catalog from an explicit model list. */
    public static ModelCatalog of(List<ModelInfo> models) {
        return new BuiltinCatalog(List.copyOf(models));
    }

    // ── ModelCatalog impl ─────────────────────────────────────

    @Override
    public List<ModelInfo> listModels() {
        return List.copyOf(modelsById.values());
    }

    @Override
    public Optional<ModelInfo> find(ModelId<?> id) {
        return Optional.ofNullable(modelsById.get(
            id.provider() + "/" + id.modelName()));
    }

    @Override
    public List<ModelInfo> search(String query) {
        var lower = query.toLowerCase();
        return modelsById.values().stream()
            .filter(m -> m.id().modelName().toLowerCase().contains(lower)
                || m.displayName().toLowerCase().contains(lower))
            .toList();
    }

    // ── Helpers ───────────────────────────────────────────────

    /** OpenRouter image-generation model（provider 固定 openrouter-images）。 */
    private static ModelInfo imageModel(String id, String display) {
        return new ModelInfo(
            ModelId.of("openrouter-images", id), display,
            Set.of(ModelCapability.IMAGE_INPUT, ModelCapability.IMAGE_OUTPUT),
            0, 0, false, PricingInfo.UNKNOWN);
    }
}
