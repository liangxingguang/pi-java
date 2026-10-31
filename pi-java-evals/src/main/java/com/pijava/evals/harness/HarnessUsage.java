package com.pijava.evals.harness;

import com.pijava.coding.agent.core.SessionStats;

/**
 * 批 32（docs/44 §3.3）：模型调用用量汇总 —— pi {@code UsageSummary} 对位。
 *
 * @param provider          provider name
 * @param model             model id
 * @param inputTokens       input tokens
 * @param outputTokens      output tokens
 * @param cacheReadTokens   cache-read tokens
 * @param cacheWriteTokens  cache-write tokens
 * @param totalTokens       total tokens
 * @param toolCalls         tool call count
 * @param estimatedCostUsd  estimated cost, {@code null} when unavailable
 */
public record HarnessUsage(
    String provider,
    String model,
    double inputTokens,
    double outputTokens,
    double cacheReadTokens,
    double cacheWriteTokens,
    double totalTokens,
    long toolCalls,
    Double estimatedCostUsd
) {

    /**
     * Project session stats into a usage summary.
     *
     * @param stats    session stats
     * @param provider provider name
     * @param model    model id
     * @param cost     cost (nullable; {@code null} when pricing is unavailable)
     * @return usage summary
     */
    public static HarnessUsage from(SessionStats stats, String provider, String model,
                                    Double cost) {
        var tokens = stats.tokens();
        return new HarnessUsage(provider, model, tokens.input(), tokens.output(),
            tokens.cacheRead(), tokens.cacheWrite(), tokens.total(), stats.toolCalls(),
            cost);
    }
}
