package com.pijava.ai.model;

import com.pijava.ai.Usage;

/**
 * Cost calculation — a line-by-line port of pi's {@code calculateCost}
 * ({@code ai/src/models.ts:900-920}; package H1, {@code docs/42 §2.1 P16}).
 *
 * <p>Four things the port deliberately copies, because the criterion is
 * "behaves like pi", not "behaves sensibly":</p>
 *
 * <ol>
 *   <li><b>The tier threshold is measured on {@code input + cacheRead + cacheWrite}</b>
 *       — output tokens do <em>not</em> count ({@code models.ts:901}).</li>
 *   <li><b>The comparison is strictly {@code >}</b>, so a request that exactly equals
 *       a threshold stays on the lower tier ({@code models.ts:905}).</li>
 *   <li><b>The highest matching tier applies to the whole request</b>, not just the
 *       excess ({@code types.ts:954}).</li>
 *   <li><b>1h cache writes are priced at {@code 2 × inputRate}</b>, not at
 *       {@code cacheWriteRate}; the 5m remainder uses {@code cacheWriteRate}
 *       ({@code models.ts:911-917}).</li>
 * </ol>
 *
 * <p><b>Declared deviation from pi:</b> pi mutates {@code usage.cost} in place and
 * returns it; Java's {@link Usage} is an immutable record, so this returns a fresh
 * {@link Usage.Cost} and the caller writes it back with {@link Usage#withCost}. The
 * resulting value is identical; only object identity differs, and no caller observes
 * that identity.</p>
 */
public final class CostCalculator {

    /** Prices are quoted per million tokens. */
    private static final double PER_MILLION = 1_000_000;

    private CostCalculator() {
    }

    /**
     * Compute the cost breakdown for a usage under a given pricing.
     *
     * @param pricing the model's pricing (rates may be {@code -1} ⇒ priced at zero)
     * @param usage   the token counts to price
     * @return a fresh cost breakdown; the caller attaches it via {@link Usage#withCost}
     */
    public static Usage.Cost calculateCost(PricingInfo pricing, Usage usage) {
        // pi: `const inputTokens = usage.input + usage.cacheRead + usage.cacheWrite;`
        double thresholdTokens = usage.input() + usage.cacheRead() + usage.cacheWrite();

        double inputRate = rate(pricing.inputPrice());
        double outputRate = rate(pricing.outputPrice());
        double cacheReadRate = rate(pricing.cacheReadPrice());
        double cacheWriteRate = rate(pricing.cacheWritePrice());

        double matchedThreshold = -1;
        for (var tier : pricing.tiers()) {
            if (thresholdTokens > tier.inputTokensAbove() && tier.inputTokensAbove() > matchedThreshold) {
                inputRate = rate(tier.inputPrice());
                outputRate = rate(tier.outputPrice());
                cacheReadRate = rate(tier.cacheReadPrice());
                cacheWriteRate = rate(tier.cacheWritePrice());
                matchedThreshold = tier.inputTokensAbove();
            }
        }

        // pi: `const longWrite = usage.cacheWrite1h ?? 0; const shortWrite = usage.cacheWrite - longWrite;`
        double longWrite = usage.cacheWrite1h() == null ? 0 : usage.cacheWrite1h();
        double shortWrite = usage.cacheWrite() - longWrite;

        double costInput = inputRate / PER_MILLION * usage.input();
        double costOutput = outputRate / PER_MILLION * usage.output();
        double costCacheRead = cacheReadRate / PER_MILLION * usage.cacheRead();
        double costCacheWrite = (cacheWriteRate * shortWrite + inputRate * 2 * longWrite) / PER_MILLION;

        return new Usage.Cost(costInput, costOutput, costCacheRead, costCacheWrite,
            costInput + costOutput + costCacheRead + costCacheWrite);
    }

    /** The {@code -1} sentinel means "unknown" and is priced at zero (see {@link PricingInfo}). */
    private static double rate(double price) {
        return price < 0 ? 0 : price;
    }
}
