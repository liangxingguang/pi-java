package com.pijava.ai.model;

import java.util.List;

/**
 * Pricing information for an LLM model.
 *
 * <p>All prices are in US dollars per million (1,000,000) tokens.
 * A value of {@code -1} means pricing data is not available for that dimension.
 * <b>Unknown dimensions are priced at zero</b> by {@link CostCalculator} — that is
 * deliberate and matches pi, whose generated catalog falls back to {@code 0} for
 * missing rates ({@code ai/scripts/generate-models.ts:1200-1207}). The {@code -1}
 * sentinel exists so that <em>display</em> can say "unknown" instead of "$0.00"
 * (see {@link #isKnown()}); it does not change the computed cost.</p>
 *
 * <p>Aligned with pi's {@code ModelCost} / {@code ModelCostTier}
 * ({@code ai/src/types.ts:941-956}).</p>
 *
 * @param inputPrice      price per 1M input tokens, or -1 if unknown
 * @param outputPrice     price per 1M output tokens, or -1 if unknown
 * @param cacheReadPrice  price per 1M cache-read tokens, or -1 if unknown
 * @param cacheWritePrice price per 1M cache-write tokens, or -1 if unknown
 * @param tiers           request-wide pricing tiers, may be empty
 */
public record PricingInfo(
    double inputPrice,
    double outputPrice,
    double cacheReadPrice,
    double cacheWritePrice,
    List<CostTier> tiers
) {

    /**
     * A request-wide pricing tier (pi {@code ModelCostTier}).
     *
     * <p>The highest matching threshold applies to the <b>whole request</b>, not just
     * the excess ({@code types.ts:954}).</p>
     *
     * @param inputTokensAbove threshold on {@code input + cacheRead + cacheWrite}
     * @param inputPrice       price per 1M input tokens under this tier
     * @param outputPrice      price per 1M output tokens under this tier
     * @param cacheReadPrice   price per 1M cache-read tokens under this tier
     * @param cacheWritePrice  price per 1M cache-write tokens under this tier
     */
    public record CostTier(
        double inputTokensAbove,
        double inputPrice,
        double outputPrice,
        double cacheReadPrice,
        double cacheWritePrice
    ) {
    }

    public PricingInfo {
        tiers = List.copyOf(tiers);
    }

    /**
     * The pre-H1 shape: cache rates unknown, no tiers.
     *
     * <p>Kept so that the ~40 existing call sites compile unchanged
     * ({@code docs/42 §8.0} 裁决 A).</p>
     *
     * @param inputPrice  price per 1M input tokens, or -1 if unknown
     * @param outputPrice price per 1M output tokens, or -1 if unknown
     */
    public PricingInfo(double inputPrice, double outputPrice) {
        this(inputPrice, outputPrice, -1, -1, List.of());
    }

    /** Sentinel indicating pricing is not available. */
    public static final PricingInfo UNKNOWN = new PricingInfo(-1, -1, -1, -1, List.of());

    /** Returns {@code true} if both input and output prices are known. */
    public boolean isKnown() {
        return inputPrice >= 0 && outputPrice >= 0;
    }

    /** Returns {@code true} if both cache prices are known. */
    public boolean isCachePricingKnown() {
        return cacheReadPrice >= 0 && cacheWritePrice >= 0;
    }
}
