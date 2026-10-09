package com.pijava.ai.provider.builtin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.CatalogCompatRules;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * xai chat 目录（包 44）—— 转录自 pi 的生成数据 {@code providers/data/xai.json}
 * （锚点 {@code 200387122}，快照 2026-10-04）。
 *
 * <p><b>4 条全量</b>（不是子集）：pi 的 xai 组只有这四条 chat 模型，且都是
 * {@code api:"openai-responses"} ⇒ 全量转录才是与 pi 同口径；未入目录的
 * {@code xai/grok-*} 会退化成 {@link ModelInfo#minimal}（api 缺席 ⇒ 走 provider 默认的
 * responses 协议 ⇒ 车道侥幸不错，但价目/窗口/思考表全丢）。</p>
 *
 * <p>⚠️ 转录源的 <b>map 键</b>是 {@code "chat:grok-4.3"}（生成器给 chat 组加的前缀），
 * 而条目内的 {@code "id"} 字段才是真 id —— 目录用后者。</p>
 *
 * <p><b>compat 不硬编</b>：四条经 {@link CatalogCompatRules#xaiResponses} 算
 * （pi 生成期常量 {@code XAI_RESPONSES_COMPAT} 的谓词形态）。</p>
 *
 * <p><b>能力集映射</b>（capabilities 是 pi-java 的发明，pi 只有 reasoning/input 两键）：
 * {@code reasoning→THINKING}、{@code "image"∈input→IMAGE_INPUT}、TEXT/TOOL_USE/STREAMING
 * 恒有；{@code PROMPT_CACHING} 当且仅当 pi 数据 {@code cost.cacheRead > 0} —— 四条都
 * {@code >0} ⇒ 四条能力集**恒同**。</p>
 */
public final class XaiModels {

    private XaiModels() {}

    private static final String PROVIDER = "xai";

    // ── 思考级别表（转录自 pi 数据的 thinkingLevelMap；null 值 ≙ 显式不支持）────
    //    ⚠️ 常量名按「off / xhigh / max 三态」起，**不**沿用 OpenRouterModels 的
    //    TLM_OFF_UNSUPPORTED（那个只含 {off:null}，本组三条都含完整 low/medium/high）
    //    —— 同名不同内容是最容易骗过实现者的坑。

    /** {@code {off:"none", …, xhigh:null, max:null}} —— grok-4.3。 */
    private static final ThinkingLevelMap TLM_NONE_OFF_NO_XHIGH_NO_MAX = tlm(
        lv("off", "none"), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", null), lv("max", null));

    /** {@code {off:null, …, xhigh:null, max:null}} —— grok-4.5。 */
    private static final ThinkingLevelMap TLM_NO_OFF_NO_XHIGH = tlm(
        lv("off", null), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", null), lv("max", null));

    /** {@code {off:null, …, xhigh:"xhigh", max:null}} —— grok-4.6、grok-4.7。 */
    private static final ThinkingLevelMap TLM_NO_OFF_XHIGH = tlm(
        lv("off", null), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", "xhigh"), lv("max", null));

    /** 全部内置 xai chat 模型（4 条，全走 openai-responses）。 */
    public static ModelCatalog catalog() {
        return BuiltinCatalog.of(List.of(
            chat("grok-4.3", "Grok 4.3", 1_000_000, 30_000, 1.25, 2.5, 0.2,
                tiers(2.5, 5, 0.4), TLM_NONE_OFF_NO_XHIGH_NO_MAX),
            chat("grok-4.5", "Grok 4.5", 500_000, 500_000, 2, 6, 0.3,
                tiers(4, 12, 0.6), TLM_NO_OFF_NO_XHIGH),
            chat("grok-4.6", "Grok 4.6", 500_000, 500_000, 2, 6, 0.5,
                tiers(4, 12, 1), TLM_NO_OFF_XHIGH),
            chat("grok-4.7", "Grok 4.7", 500_000, 500_000, 2, 6, 0.5,
                tiers(4, 12, 1), TLM_NO_OFF_XHIGH)));
    }

    /**
     * 一条 xai chat 模型：{@code api} 组件必带 {@code "openai-responses"}
     * （pi 数据每条都是该值）；compat 走 {@link CatalogCompatRules#xaiResponses}。
     *
     * <p>{@code baseUrl} 组件**缺席**（{@code null}）：pi 数据每条都写
     * {@code "https://api.x.ai/v1"}，但那与 provider 默认值同源 ⇒ 由
     * {@code ConfigurableProvider.effectiveOptions} 在请求期补，不逐条转录
     * （与 {@code OpenRouterModels} 同口径）。</p>
     */
    private static ModelInfo chat(String id, String display, int contextWindow, int maxTokens,
            double in, double out, double cacheRead, List<PricingInfo.CostTier> tiers,
            ThinkingLevelMap levels) {
        return new ModelInfo(
            ModelId.of(PROVIDER, id), display,
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT, ModelCapability.TOOL_USE,
                ModelCapability.THINKING, ModelCapability.STREAMING, ModelCapability.PROMPT_CACHING),
            contextWindow, maxTokens, false,
            new PricingInfo(in, out, cacheRead, 0, tiers), levels, Map.of(), Map.of(),
            CatalogCompatRules.xaiResponses(PROVIDER), "openai-responses", null);
    }

    /** pi 每条 xai 模型的 {@code cost.tiers}（{@code inputTokensAbove:200000}）。 */
    private static List<PricingInfo.CostTier> tiers(double in, double out, double cacheRead) {
        return List.of(new PricingInfo.CostTier(200_000, in, out, cacheRead, 0));
    }

    /** 一条级别表项：值 {@code null} ≙ pi 的显式 null（该级别不支持）。 */
    private static Map.Entry<ModelThinkingLevel, Optional<String>> lv(String level, String value) {
        return Map.entry(ModelThinkingLevel.parse(level).orElseThrow(),
            Optional.ofNullable(value));
    }

    @SafeVarargs
    private static ThinkingLevelMap tlm(Map.Entry<ModelThinkingLevel, Optional<String>>... entries) {
        var map = new LinkedHashMap<ModelThinkingLevel, Optional<String>>();
        for (var entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return ThinkingLevelMap.of(map);
    }
}
