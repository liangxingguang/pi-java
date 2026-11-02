package com.pijava.ai.provider.builtin;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.pijava.ai.model.PricingInfo;

/**
 * OpenRouter per-token pricing → pi {@link PricingInfo}（百万 token 计）。
 *
 * <p>逐字对齐 pi {@code openRouterCost}（{@code openrouter-api.ts:40-73}）：
 * 缺失、负数、非数字一律走 fallback；基础费率 fallback 为 {@code 0}，
 * 覆盖档的 fallback 是基础费率；只认 {@code min_prompt_tokens} 档，
 * 带 {@code utc_*} 的时间档跳过；结果保留六位小数。</p>
 */
public final class OpenRouterCost {

    private OpenRouterCost() {}

    /** 把一条 OpenRouter {@code pricing} 对象折算成 {@link PricingInfo}。 */
    public static PricingInfo fromWire(JsonNode pricing) {
        if (pricing == null || !pricing.isObject()) {
            return new PricingInfo(0, 0, 0, 0, List.of());
        }
        double input = perMillion(text(pricing, "prompt"), 0);
        double output = perMillion(text(pricing, "completion"), 0);
        double cacheRead = perMillion(text(pricing, "input_cache_read"), 0);
        double cacheWrite = perMillion(text(pricing, "input_cache_write"), 0);

        var tiers = new ArrayList<PricingInfo.CostTier>();
        var overrides = pricing.path("overrides");
        if (overrides.isArray()) {
            for (var override : overrides) {
                if (!override.isObject() || !override.path("min_prompt_tokens").isNumber()) {
                    continue;
                }
                if (override.has("utc_start") || override.has("utc_end")
                        || override.has("utc_days")) {
                    continue;
                }
                tiers.add(new PricingInfo.CostTier(
                    override.path("min_prompt_tokens").asDouble(),
                    perMillion(text(override, "prompt"), input),
                    perMillion(text(override, "completion"), output),
                    perMillion(text(override, "input_cache_read"), cacheRead),
                    perMillion(text(override, "input_cache_write"), cacheWrite)));
            }
        }
        return new PricingInfo(input, output, cacheRead, cacheWrite, tiers);
    }

    /** pi {@code perMillion}：per-token 值 × 1e6，保留六位小数；负数/非数字 ⇒ fallback。 */
    static double perMillion(String value, double fallback) {
        double perToken = parseDouble(value);
        if (!Double.isFinite(perToken) || perToken < 0) {
            return fallback;
        }
        // 先换算成百万 token 计，再保留六位小数（pi 的 toFixed(6)）。
        double perMillion = perToken * 1_000_000d;
        return Math.round(perMillion * 1_000_000d) / 1_000_000d;
    }

    private static double parseDouble(String value) {
        if (value == null || value.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
