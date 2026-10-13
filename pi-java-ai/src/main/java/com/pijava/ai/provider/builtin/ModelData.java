package com.pijava.ai.provider.builtin;

import java.util.List;
import java.util.Set;

import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.BuiltinModelCatalogs;

/**
 * Built-in model data without npm-generated shards.
 *
 * <p>批 3a 起，主流 17 provider 的目录由 {@link BuiltinModelCatalogs} 从
 * {@code resources/catalog/*.json}（npm {@code pi-ai@1.1.0} 数据）装载；本类只剩
 * <b>ollama</b>（pi 无生成分片，本地模型走 models.json 语义）与生成时间戳委托。</p>
 */
public final class ModelData {

    private ModelData() {}

    /**
     * 静态模型表的快照时间 —— 委托 {@link BuiltinModelCatalogs#generatedAt()}
     * （与携带的 {@code .manifest.json} 同源）。远程目录守卫
     * （{@code RemoteCatalogProvider}）会丢弃 Last-Modified 不晚于此值的 overlay。
     */
    public static java.time.Instant generatedAt() {
        return BuiltinModelCatalogs.generatedAt();
    }

    /** Local Ollama models (no pricing; pi ships no generated shard for them). */
    public static ModelCatalog ollamaModels() {
        return catalog(
            model("llama3.2", "Llama 3.2", 128_000, 8_192),
            model("qwen2.5-coder", "Qwen2.5 Coder", 128_000, 8_192));
    }

    private static ModelCatalog catalog(ModelInfo... models) {
        return BuiltinCatalog.of(List.of(models));
    }

    private static ModelInfo model(String name, String display, int maxIn, int maxOut) {
        // ollama 旧条目的 compat 历来是 NONE（手写规则的常量表无 ollama 臂），显式给 NONE。
        return new ModelInfo(
            ModelId.of("ollama", name), display, chatCaps(), maxIn, maxOut, false,
            PricingInfo.UNKNOWN);
    }

    private static Set<ModelCapability> chatCaps() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE,
            ModelCapability.STREAMING);
    }
}
