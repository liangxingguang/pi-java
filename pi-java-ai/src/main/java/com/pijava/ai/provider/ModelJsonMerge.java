package com.pijava.ai.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.ModelsJsonSchema.Cost;
import com.pijava.ai.provider.ModelsJsonSchema.ModelDef;
import com.pijava.ai.provider.ModelsJsonSchema.ModelOverrideDef;
import com.pijava.ai.provider.ModelsJsonSchema.ProviderDef;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * D-P1（{@code docs/65}）：把 models.json 中命中内置 provider 的配置<b>合并</b>进
 * 内置模型目录 —— pi {@code coding-agent/src/core/provider-composer.ts:184-221} 的
 * {@code applyModelsJson} ＋ {@code applyModelOverride}。
 *
 * <p>三阶段同 pi：</p>
 * <ol>
 *   <li><b>provider 级覆盖</b>：baseUrl/compat/headers 叠加到每个内置模型。</li>
 *   <li><b>models[] upsert</b>：同 id 整条替换（api 缺席时继承被替换模型的 api，R1）、
 *       新 id 追加。</li>
 *   <li><b>modelOverrides 最顶层</b>：逐字段覆盖（在最后）。</li>
 * </ol>
 */
public final class ModelJsonMerge {

    private ModelJsonMerge() {}

    /**
     * 合并一个 provider 条目与其内置模型目录。
     *
     * @throws IllegalStateException provider 条目除 name 外全空（pi
     *         {@code provider-composer.ts:193-207}）
     */
    public static List<ModelInfo> merge(String providerId, List<ModelInfo> base,
                                         ProviderDef cfg) {
        if (cfg == null || !hasConfiguredFields(cfg)) {
            throw new IllegalStateException(
                "models.json provider \"" + providerId + "\": must specify \"baseUrl\","
                + " \"headers\", \"compat\", \"modelOverrides\", \"apiKey\", or \"models\"");
        }

        var providerHeaders = cfg.headers() == null
            ? Map.<String, String>of() : cfg.headers();

        // Phase A：provider 级 baseUrl/compat/headers 叠加到每个内置模型。
        var working = new ArrayList<ModelInfo>(base.size());
        for (var m : base) {
            var headers = mergeStringMaps(m.headers(), providerHeaders);
            String baseUrl = nonBlank(cfg.baseUrl()) ? cfg.baseUrl() : m.baseUrl();
            var compat = ModelsJsonCompat.mergeNormalizedCompat(
                providerId, m.id().modelName(), m.compat(), cfg.compat());
            working.add(withFields(m, m.api(), baseUrl, headers, compat));
        }

        // Phase B：models[] upsert。headers 分层记录（次序 base→override→models[]）。
        var modelDefHeaders = new LinkedHashMap<Integer, Map<String, String>>();
        if (cfg.models() != null) {
            for (var d : cfg.models()) {
                var converted = ModelsJsonConfig.toModelInfo(providerId, cfg, d);
                int idx = indexOfId(working, d.id());
                if (blank(d.api()) && blank(cfg.api())
                        && idx >= 0 && working.get(idx).api() != null) {
                    // R1：model/provider api 皆缺席 ⇒ 继承被替换模型原 api（不抛错）。
                    converted = withFields(converted, working.get(idx).api(),
                        converted.baseUrl(), converted.headers(), converted.compat());
                }
                if (idx >= 0) {
                    if (!converted.headers().isEmpty()) {
                        modelDefHeaders.put(idx, converted.headers());
                    }
                    // 保留被替换模型已合并的 headers（base+provider 层）；models[] 层
                    // 在最后合成时叠加（同键赢）。
                    converted = withFields(converted, converted.api(), converted.baseUrl(),
                        working.get(idx).headers(), converted.compat());
                    working.set(idx, converted);
                } else {
                    if (!converted.headers().isEmpty()) {
                        modelDefHeaders.put(working.size(), converted.headers());
                    }
                    working.add(converted);
                }
            }
        }

        // Phase C：modelOverrides —— 最顶层逐字段覆盖；headers 按
        // 「base → override → models[]」次序最后合成。
        if (cfg.modelOverrides() != null) {
            for (var entry : cfg.modelOverrides().entrySet()) {
                int idx = indexOfId(working, entry.getKey());
                if (idx < 0) {
                    continue;
                }
                working.set(idx, applyOverride(providerId, working.get(idx), entry.getValue()));
            }
        }

        // Headers 三层最终合成（models[] 同键最后赢，pi rawModelHeaders :408-421）。
        for (int i = 0; i < working.size(); i++) {
            var mdh = modelDefHeaders.get(i);
            if (mdh == null || mdh.isEmpty()) {
                continue;
            }
            var m = working.get(i);
            working.set(i, withFields(m, m.api(), m.baseUrl(),
                mergeStringMaps(m.headers(), mdh), m.compat()));
        }

        return List.copyOf(working);
    }

    /** 一条 {@code modelOverrides} 值的逐字段覆盖（pi {@code applyModelOverride :108-134}）。 */
    private static ModelInfo applyOverride(String providerId, ModelInfo m, ModelOverrideDef o) {
        String displayName = o.name() != null && !o.name().isBlank() ? o.name() : m.displayName();
        PricingInfo pricing = o.cost() != null ? mergeCost(m.pricing(), o.cost()) : m.pricing();
        ThinkingLevelMap levelMap = o.thinkingLevelMap() != null
            ? mergeThinkingMap(m.thinkingLevelMap(), o.thinkingLevelMap())
            : m.thinkingLevelMap();
        int contextWindow = o.contextWindow() != null ? o.contextWindow() : m.maxInputTokens();
        int maxTokens = o.maxTokens() != null ? o.maxTokens() : m.maxOutputTokens();
        var samplingParams = mergeObjectMaps(m.samplingParams(), o.samplingParams());
        var capabilities = new java.util.LinkedHashSet<>(m.capabilities());
        if (o.input() != null) {
            if (o.input().contains("image")) {
                capabilities.add(ModelCapability.IMAGE_INPUT);
            } else {
                capabilities.remove(ModelCapability.IMAGE_INPUT);
            }
        }
        if (o.reasoning() != null) {
            if (o.reasoning()) {
                capabilities.add(ModelCapability.THINKING);
            } else {
                capabilities.remove(ModelCapability.THINKING);
            }
        }
        var compat = o.compat() != null
            ? ModelsJsonCompat.mergeNormalizedCompat(
                providerId, m.id().modelName(), m.compat(), o.compat())
            : m.compat();

        // headers 由调用方分层合成（次序要求）—— o.headers() 经 extra-like 方式插入：
        // 直接在此合并且不覆盖 models[] 层不可能，故先与 base 层合并（models[] 层最后合成）。
        var headers = o.headers() != null
            ? mergeStringMaps(m.headers(), o.headers()) : m.headers();

        return rebuild(m, m.id(), displayName, capabilities, contextWindow, maxTokens,
            pricing, levelMap, headers, samplingParams, compat, m.api(), m.baseUrl());
        // R3：o.promptCache() 在 pi-java 无对应组件（CacheWarmer 未移植）⇒ 解析但忽略。
    }

    /** cost 键级合并（pi {@code applyModelOverride:117-125}）。 */
    private static PricingInfo mergeCost(PricingInfo base, Cost override) {
        return new PricingInfo(
            override.input() != null ? override.input() : base.inputPrice(),
            override.output() != null ? override.output() : base.outputPrice(),
            override.cacheRead() != null ? override.cacheRead() : base.cacheReadPrice(),
            override.cacheWrite() != null ? override.cacheWrite() : base.cacheWritePrice(),
            override.tiers() != null ? costTiers(override.tiers()) : base.tiers());
    }

    private static List<PricingInfo.CostTier> costTiers(List<Cost.CostTierDef> defs) {
        var tiers = new ArrayList<PricingInfo.CostTier>(defs.size());
        for (var d : defs) {
            tiers.add(new PricingInfo.CostTier(d.inputTokensAbove(), d.input(), d.output(),
                d.cacheRead(), d.cacheWrite()));
        }
        return List.copyOf(tiers);
    }

    /** thinkingLevelMap 键级合并：raw map 键转换后覆盖（pi :113-115）。 */
    private static ThinkingLevelMap mergeThinkingMap(ThinkingLevelMap base,
                                                     Map<String, String> override) {
        var merged = new LinkedHashMap<>(base.entries());
        override.forEach((key, value) -> ModelThinkingLevel.parse(key).ifPresent(level ->
            merged.put(level, Optional.ofNullable(value))));
        return ThinkingLevelMap.of(merged);
    }

    // ── ModelInfo 重建 helpers ───────────────────────────────────────

    private static ModelInfo withFields(ModelInfo m, String api, String baseUrl,
                                         Map<String, String> headers,
                                         com.pijava.ai.catalog.ModelCompat compat) {
        return rebuild(m, m.id(), m.displayName(), m.capabilities(),
            m.maxInputTokens(), m.maxOutputTokens(), m.pricing(), m.thinkingLevelMap(),
            headers, m.samplingParams(), compat, api, baseUrl);
    }


    private static ModelInfo rebuild(ModelInfo m, ModelId<?> id, String displayName,
                                     java.util.Set<ModelCapability> capabilities,
                                     int contextWindow, int maxTokens,
                                     PricingInfo pricing, ThinkingLevelMap levelMap,
                                     Map<String, String> headers,
                                     Map<String, Object> samplingParams,
                                     com.pijava.ai.catalog.ModelCompat compat,
                                     String api, String baseUrl) {
        return new ModelInfo(id, displayName, capabilities, contextWindow, maxTokens,
            m.deprecated(), pricing, levelMap, headers, samplingParams, compat, api, baseUrl);
    }

    private static Map<String, String> mergeStringMaps(Map<String, String> base,
                                                        Map<String, String> override) {
        if (override == null || override.isEmpty()) {
            return base;
        }
        var merged = new LinkedHashMap<>(base);
        merged.putAll(override);
        return merged;
    }

    private static Map<String, Object> mergeObjectMaps(Map<String, Object> base,
                                                        Map<String, Object> override) {
        if (override == null || override.isEmpty()) {
            return base;
        }
        var merged = new LinkedHashMap<>(base);
        merged.putAll(override);
        return merged;
    }

    private static int indexOfId(List<ModelInfo> models, String id) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id().modelName().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** pi {@code :193} 的「除 name 外有任何配置」检查。 */
    private static boolean hasConfiguredFields(ProviderDef cfg) {
        return nonBlank(cfg.baseUrl()) || nonBlank(cfg.apiKey()) || nonBlank(cfg.api())
            || (cfg.models() != null && !cfg.models().isEmpty())
            || (cfg.headers() != null && !cfg.headers().isEmpty())
            || (cfg.modelOverrides() != null && !cfg.modelOverrides().isEmpty())
            || cfg.compat() != null;
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
