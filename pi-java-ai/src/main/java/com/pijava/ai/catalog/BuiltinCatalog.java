package com.pijava.ai.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.ai.model.ModelId;

/**
 * Map-backed {@link ModelCatalog} for built-in models.
 *
 * <p>主流 18 provider 的 chat 目录与 OpenRouter 图片目录都来自
 * {@code catalog-generator/} 生成的分片（见 {@code provider.BuiltinModelCatalogs}）；
 * 本类只保留 Map 支撑与各种装配链共用的 {@link #of} 工厂。</p>
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

}
