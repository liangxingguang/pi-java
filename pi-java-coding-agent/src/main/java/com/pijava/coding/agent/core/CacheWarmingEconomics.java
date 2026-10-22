package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.Usage;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.CostCalculator;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

/**
 * 批 13（docs/24 T1）：{@link CacheWarmer} 的纯函数与经济学（pi cache-warmer.ts
 * :29-58 三纯函数＋:61-86 计价助手＋:378-400 决策，全逐字）。拆出只为单文件
 * ≤500 行（评审 I2）；{@code CacheWarmer} 的 start/evaluate 是本类的调用点。
 */
final class CacheWarmingEconomics {

    private CacheWarmingEconomics() {}

    /** pi :29-32：TTL 的 90% 且至少留 10 秒余量；TTL ≤ 10s 不 warm。 */
    static Long getCacheWarmingDelayMs(long ttlMs) {
        if (ttlMs <= 10_000) {
            return null;
        }
        return Math.max(1, (long) Math.floor(Math.min(ttlMs * 0.9, ttlMs - 10_000)));
    }

    /**
     * pi :39-46：{@code options.cacheRetention ?? PI_CACHE_RETENTION env ?? "short"}
     * （java 的 {@code StreamOptions} 无 env 键——与车道同读进程 env，经
     * {@link CompatResolver#resolveCacheRetention} 同一判据）；{@code none} ⇒ 不 warm；
     * 读 {@code promptCache} 对应档秒数。
     */
    static Long getPromptCacheTtlMs(ModelInfo model, StreamOptions options) {
        var retention = CompatResolver.resolveCacheRetention(
            options == null ? Optional.empty() : options.cacheRetention(),
            System.getenv("PI_CACHE_RETENTION"));
        if (retention == CacheRetention.NONE) {
            return null;
        }
        var lifetimes = model.promptCache();
        var seconds = retention == CacheRetention.LONG
            ? (lifetimes == null ? null : lifetimes.longSeconds())
            : (lifetimes == null ? null : lifetimes.shortSeconds());
        return seconds == null ? null : seconds.longValue() * 1000L;
    }

    /**
     * pi :55-58：anthropic-messages 的预算思考（非 adaptive）从 max_tokens 推
     * budget_tokens——{@code maxTokens=1} 重放会改变缓存键且可能真思考 ⇒ 不可重放。
     */
    static boolean isReplayable(ModelInfo model, StreamOptions options) {
        if (options == null || options.reasoning().isEmpty()
                || !"anthropic-messages".equals(model.api())) {
            return true;
        }
        return model.compat().forceAdaptiveThinking();
    }

    /** pi :378-400 逐字。 */
    static CacheWarming.Decision evaluate(
            Function<ModelId<?>, Optional<ModelInfo>> modelInfo,
            ModelId<?> model, List<Entry> branch, String phase) {
        var pricing = modelInfo.apply(model)
            .orElseGet(() -> ModelInfo.minimal(model))
            .pricing();
        var promptTokens = lastPromptTokens(branch);
        var cacheHitCost = price(pricing, 0, 0, promptTokens, 0);
        // pi :382-385：有 cacheWrite 价 ⇒ miss 落在 cacheWrite 桶，否则落在 input 桶。
        var cacheMissCost = pricing.cacheWritePrice() > 0
            ? price(pricing, 0, 0, 0, promptTokens)
            : price(pricing, promptTokens, 0, 0, 0);
        var warmCost = price(pricing, 0, 1, promptTokens, 0);
        var missCost = Math.max(0, cacheMissCost - cacheHitCost);
        var continuationProbability = "idle".equals(phase)
            ? CacheWarmer.IDLE_CONTINUATION_PROBABILITY : 1;
        var economicsAvailable = promptTokens > 0 && (cacheHitCost > 0 || cacheMissCost > 0);
        var expectedSavings = continuationProbability * missCost - warmCost;
        return new CacheWarming.Decision(phase, warmCost, missCost,
            continuationProbability, expectedSavings, economicsAvailable,
            expectedSavings >= CacheWarmer.MINIMUM_EXPECTED_SAVINGS
                ? CacheWarming.Action.WARM : CacheWarming.Action.STOP);
    }

    /** pi :61-70：分支上最近一次真实请求的 prompt 量（provider 上报口径）。 */
    private static long lastPromptTokens(List<Entry> entries) {
        for (var index = entries.size() - 1; index >= 0; index--) {
            if (entries.get(index) instanceof Entry.Message messageEntry
                    && messageEntry.message() instanceof Message.AssistantMessage assistant) {
                var usage = assistant.usage();
                // pi 假设 usage 恒在；缺失按 0 收（⇒ economics unavailable，安全方向）。
                return usage == null ? 0
                    : (long) (usage.input() + usage.cacheRead() + usage.cacheWrite());
            }
        }
        return 0;
    }

    /** pi :72-86：按目录价目给 token 组合计价（{@link CostCalculator} 逐行移植对位）。 */
    private static double price(PricingInfo pricing, double input, double output,
                                double cacheRead, double cacheWrite) {
        var usage = new Usage(input, output, cacheRead, cacheWrite, null, null, 0,
            Usage.Cost.zero());
        return CostCalculator.calculateCost(pricing, usage).total();
    }
}
