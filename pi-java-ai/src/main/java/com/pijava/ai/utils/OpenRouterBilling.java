package com.pijava.ai.utils;

import java.util.Map;

import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;

/**
 * docs/15（B267）：OpenRouter 实报账单 —— pi {@code utils/openrouter-cost.ts:24-54}
 * 的逐条移植（{@code isOpenRouterModel} 与 {@code applyOpenRouterReportedCost}）。
 *
 * <p>与 pi 的差异只有一处：pi 就地 mutate {@code usage.cost}，Java 的 {@link Usage}
 * 不可变 ⇒ 返回新对象；无有效账单时原样返回，调用方无需区分。</p>
 */
public final class OpenRouterBilling {

    /** Internal {@code ApiOptions.extra} key: provider 预计算的 OpenRouter 判定。 */
    public static final String EXTRA_KEY = "openRouterModel";

    private static final String OPENROUTER_TOKEN = "openrouter.ai";

    private OpenRouterBilling() {}

    /**
     * pi {@code isOpenRouterModel}（{@code :24-26}）：provider 名或 baseUrl 任一命中。
     *
     * @param provider provider 名（pi 的 {@code model.provider}）
     * @param baseUrl  effective base URL；可为 {@code null}
     * @return 是否为 OpenRouter 车道
     */
    public static boolean isOpenRouterModel(String provider, String baseUrl) {
        return "openrouter".equals(provider)
            || (baseUrl != null && baseUrl.contains(OPENROUTER_TOKEN));
    }

    /**
     * 车道读 provider 预计算的判定（{@code ConfigurableProvider} 写入）。
     *
     * @param options 车道选项；键缺席 ⇒ {@code false}
     * @return 是否为 OpenRouter 车道
     */
    public static boolean isOpenRouterModel(ApiOptions options) {
        return options != null
            && Boolean.TRUE.equals(options.extra().get(EXTRA_KEY));
    }

    /**
     * pi {@code applyOpenRouterReportedCost}（{@code :29-54}）。
     *
     * <p>只替换 {@code cost.total} 与 {@code totalFromProvider}，token 分量与目录
     * cost 分量都保持原值。边界逐条照抄：</p>
     *
     * <ul>
     *   <li>{@code cost} 无效（缺席／null／非数字／NaN／Infinity／负数）⇒ 原样返回。</li>
     *   <li>{@code is_byok === false} ⇒ {@code total = cost}。</li>
     *   <li>{@code is_byok === true} ⇒ 需要 {@code cost_details}；{@code upstream_inference_cost}
     *       <b>键缺席</b> ⇒ 原样返回（pi 的 {@code undefined} 不满足 {@code === null}）；</li>
     *   <li>显式 {@code null} ⇒ 用 prompt＋completions 求和，任一无效 ⇒ 原样返回；</li>
     *   <li>其余 {@code is_byok} 形态 ⇒ 原样返回。</li>
     * </ul>
     *
     * @param usage    目录估算后的 usage
     * @param reported provider 原始 usage 视图（pi 键名）
     * @return 带实报 total 的新 usage；账目不完整时返回 {@code usage}
     */
    public static Usage applyReportedCost(Usage usage, Map<String, Object> reported) {
        if (usage == null || reported == null) {
            return usage;
        }
        Object cost = reported.get("cost");
        if (!isValidCost(cost)) {
            return usage;
        }
        double reportedCost = ((Number) cost).doubleValue();
        Object byok = reported.get("is_byok");
        if (Boolean.FALSE.equals(byok)) {
            return withTotal(usage, reportedCost);
        }
        if (!Boolean.TRUE.equals(byok)) {
            return usage;
        }
        Object details = reported.get("cost_details");
        if (!(details instanceof Map<?, ?> map)) {
            return usage;
        }
        // ⚠️ 必须用 containsKey 区分「键缺席」与「显式 null」：两者 Map.get() 都返回 null，
        // 但 pi 只对显式 null 走两分量求和分支（undefined 让 isValidCost 失败 ⇒ 原样返回）。
        if (!map.containsKey("upstream_inference_cost")) {
            return usage;
        }
        Object upstream = map.get("upstream_inference_cost");
        double upstreamCost;
        if (upstream == null) {
            Object prompt = map.get("upstream_inference_prompt_cost");
            Object completions = map.get("upstream_inference_completions_cost");
            if (!isValidCost(prompt) || !isValidCost(completions)) {
                return usage;
            }
            upstreamCost = ((Number) prompt).doubleValue() + ((Number) completions).doubleValue();
        } else {
            if (!isValidCost(upstream)) {
                return usage;
            }
            upstreamCost = ((Number) upstream).doubleValue();
        }
        return withTotal(usage, reportedCost + upstreamCost);
    }

    /** pi {@code isValidCost}（{@code :18-20}）：Number、有限、≥ 0。 */
    private static boolean isValidCost(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        double v = number.doubleValue();
        return Double.isFinite(v) && v >= 0;
    }

    private static Usage withTotal(Usage usage, double total) {
        var cost = usage.cost();
        return usage.withCost(new Usage.Cost(cost.input(), cost.output(), cost.cacheRead(),
            cost.cacheWrite(), total, true));
    }
}
