package com.pijava.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Token and cost usage reported by an LLM provider.
 *
 * <p>Aligned with pi's {@code Usage} from {@code @earendil-works/pi-ai}
 * (input/output/cacheRead/cacheWrite/totalTokens/cost.total). Optional
 * {@code cacheWrite1h}/{@code reasoning} fields are omitted from JSON when
 * absent. Phase 4 session stats ({@code SessionStats}) and usage-bearing
 * records ({@code UsageRecord}, compaction entries) consume this type.</p>
 *
 * @param input        input tokens
 * @param output       output tokens (includes reasoning, when reported)
 * @param cacheRead    cache-read tokens
 * @param cacheWrite   cache-write tokens
 * @param cacheWrite1h cache-write tokens with 1h retention (Anthropic only)
 * @param reasoning    reasoning/thinking tokens, a subset of {@code output}
 * @param totalTokens  total tokens for the request
 * @param cost         cost breakdown (USD)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Usage(
    double input,
    double output,
    double cacheRead,
    double cacheWrite,
    Double cacheWrite1h,
    Double reasoning,
    double totalTokens,
    Cost cost
) {

    /** Cost breakdown in USD. */
    public record Cost(
        double input,
        double output,
        double cacheRead,
        double cacheWrite,
        double total
    ) {
        /** Zero cost. */
        public static Cost zero() {
            return new Cost(0, 0, 0, 0, 0);
        }
    }

    /**
     * Create a usage with only input/output token counts and zero cost.
     * Convenience for providers that do not report cache/cost breakdowns.
     */
    public static Usage of(double input, double output) {
        return new Usage(input, output, 0, 0, null, null,
            input + output, Cost.zero());
    }

    /** Create a usage with explicit totals and zero cost. */
    public static Usage of(double input, double output, double totalTokens) {
        return new Usage(input, output, 0, 0, null, null,
            totalTokens, Cost.zero());
    }
}
