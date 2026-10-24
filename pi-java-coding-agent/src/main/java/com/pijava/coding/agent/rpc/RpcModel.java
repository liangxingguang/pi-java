package com.pijava.coding.agent.rpc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.catalog.InputLimits;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.ProviderConfig;

/**
 * get_state 的 {@code model} 线格（批 20，B31）——pi {@code RpcSessionState.model}
 * 是整个运行时 {@code Model} 对象（rpc-mode.ts:450；schema 见
 * ai/providers/model-schema.ts:3-96 与 ai/types.ts:819-857），{@code undefined}
 * 字段被 {@code JSON.stringify} 省略 ⇒ 本记录 {@code NON_NULL} 同形。
 *
 * <p>映射裁决（docs/31 §6）：cost 未知价 −1 ⇒ 0（裁决④，pi 目录生成回落语义）；
 * {@code compat} ＝ {@link ModelCompat#NONE} ⇒ 省略，非 NONE ⇒ 发<b>解析后</b>全
 * 记录（裁决③，pi 线格是 authored 稀疏对象——窄差 D118）；{@code type} 恒省略
 * （java 全 chat 模型，pi chat 模型同省略——零差异不登记）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RpcModel(
    String id,
    String name,
    String api,
    String provider,
    String baseUrl,
    List<String> input,
    RpcInputLimits inputLimits,
    RpcModelCost cost,
    Map<String, String> headers,
    Boolean reasoning,
    Map<String, String> thinkingLevelMap,
    Map<String, Integer> promptCache,
    Integer contextWindow,
    Integer maxTokens,
    Map<String, Object> samplingParams,
    Map<String, Object> samplingParamsByThinkingLevel,
    JsonNode compat
) {

    /** pi {@code ModelCost}（model-schema.ts:39-46）：四价必填，tiers 空 ⇒ 省略。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RpcModelCost(double input, double output, double cacheRead,
                               double cacheWrite, List<RpcCostTier> tiers) {
        /** pi {@code ModelCostTier}（model-schema.ts:33-38）。 */
        public record RpcCostTier(double inputTokensAbove, double input, double output,
                                  double cacheRead, double cacheWrite) {}
    }

    /** pi {@code ModelInputLimits}（model-schema.ts:78-83）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RpcInputLimits(Integer maxRequestBytes, RpcImageLimits images) {
        /** pi {@code ModelImageInputLimits}（model-schema.ts:64-76）。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record RpcImageLimits(RpcResizeOptions resize, Integer maxPerMessage,
                                     Integer maxPerRequest) {
            /** pi {@code modelImageResizeOptions}（model-schema.ts:48-60）。 */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            public record RpcResizeOptions(Integer maxWidth, Integer maxHeight,
                                           Integer maxBytes, Integer jpegQuality) {}
        }
    }

    /**
     * {@link ModelInfo} → pi {@code Model} 线格。
     *
     * @param info   生效目录解析的模型元数据（{@code ModelInfo.minimal} 容忍路径
     *               得零值形状——窄差 D121）
     * @param config 所属 provider 的静态配置（api/baseUrl 缺省源；可空 ⇒ 两键省略）
     * @param mapper {@code compat} 树化的 mapper
     */
    public static RpcModel of(ModelInfo info, ProviderConfig config, ObjectMapper mapper) {
        var input = new ArrayList<String>(2);
        input.add("text");
        if (info.capabilities().contains(ModelCapability.IMAGE_INPUT)) {
            input.add("image");
        }
        return new RpcModel(
            info.id().modelName(),
            info.displayName(),
            info.api() != null ? info.api()
                : (config != null ? config.defaultProtocol().wireName() : null),
            info.id().provider(),
            info.baseUrl() != null ? info.baseUrl()
                : (config != null ? config.defaultBaseUrl() : null),
            List.copyOf(input),
            wireInputLimits(info.inputLimits()),
            wireCost(info.pricing()),
            info.headers().isEmpty() ? null : info.headers(),
            info.capabilities().contains(ModelCapability.THINKING),
            wireThinkingLevelMap(info.thinkingLevelMap()),
            wirePromptCache(info.promptCache()),
            info.maxInputTokens(),
            info.maxOutputTokens(),
            info.samplingParams().isEmpty() ? null : info.samplingParams(),
            wireSamplingByLevel(info.samplingParamsByThinkingLevel()),
            ModelCompat.NONE.equals(info.compat()) ? null : mapper.valueToTree(info.compat()));
    }

    private static RpcModelCost wireCost(PricingInfo pricing) {
        List<RpcModelCost.RpcCostTier> tiers = null;
        if (!pricing.tiers().isEmpty()) {
            tiers = pricing.tiers().stream()
                .map(t -> new RpcModelCost.RpcCostTier(t.inputTokensAbove(), zero(t.inputPrice()),
                    zero(t.outputPrice()), zero(t.cacheReadPrice()), zero(t.cacheWritePrice())))
                .toList();
        }
        return new RpcModelCost(zero(pricing.inputPrice()), zero(pricing.outputPrice()),
            zero(pricing.cacheReadPrice()), zero(pricing.cacheWritePrice()), tiers);
    }

    /** 裁决④：java 的未知价 −1 ⇒ pi 目录生成回落 0（pi 线格永不出现 −1）。 */
    private static double zero(double price) {
        return price < 0 ? 0 : price;
    }

    /**
     * pi {@code ThinkingLevelMap}（{@code Partial<Record<level, string|null>>}）：
     * 键缺席 ⇒ 整键省略；键在场＋显式不支持 ⇒ 线格 <b>null 值保真</b>（Jackson 默认
     * 序列化 null map 值，类级 NON_NULL 不及 map 内容）。
     */
    private static Map<String, String> wireThinkingLevelMap(
            com.pijava.ai.thinking.ThinkingLevelMap map) {
        if (map == null || map.entries().isEmpty()) {
            return null;
        }
        var wire = new LinkedHashMap<String, String>();
        map.entries().forEach((level, mapped) -> wire.put(level.label(), mapped.orElse(null)));
        return wire;
    }

    private static Map<String, Integer> wirePromptCache(
            com.pijava.ai.catalog.PromptCacheLifetimes cache) {
        if (cache == null) {
            return null;
        }
        var wire = new LinkedHashMap<String, Integer>();
        if (cache.shortSeconds() != null) {
            wire.put("short", cache.shortSeconds());
        }
        if (cache.longSeconds() != null) {
            wire.put("long", cache.longSeconds());
        }
        return wire.isEmpty() ? null : wire;
    }

    private static Map<String, Object> wireSamplingByLevel(
            Map<com.pijava.ai.thinking.ModelThinkingLevel, Map<String, Object>> byLevel) {
        if (byLevel == null || byLevel.isEmpty()) {
            return null;
        }
        var wire = new LinkedHashMap<String, Object>();
        byLevel.forEach((level, params) -> wire.put(level.label(), params));
        return wire;
    }

    private static RpcInputLimits wireInputLimits(InputLimits limits) {
        if (limits == null) {
            return null;
        }
        RpcInputLimits.RpcImageLimits images = null;
        if (limits.images() != null) {
            var resize = limits.images().resize() == null ? null
                : new RpcInputLimits.RpcImageLimits.RpcResizeOptions(
                    limits.images().resize().maxWidth(), limits.images().resize().maxHeight(),
                    limits.images().resize().maxBytes(), limits.images().resize().jpegQuality());
            images = new RpcInputLimits.RpcImageLimits(
                resize, limits.images().maxPerMessage(), limits.images().maxPerRequest());
        }
        if (limits.maxRequestBytes() == null && images == null) {
            return null;
        }
        return new RpcInputLimits(limits.maxRequestBytes(), images);
    }
}
