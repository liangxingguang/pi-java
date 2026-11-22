package com.pijava.ai.api;

import java.util.Locale;
import java.util.Optional;

import com.pijava.ai.Usage;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.CostCalculator;
import com.pijava.ai.model.PricingInfo;

/**
 * pi {@code packages/ai/src/cache-warming.ts}（锚点 {@code a00a5048d}）的公开面：
 * 缓存预热的**策略与经济学**——何时该刷新、请求能否安全重放、刷新是否划算。
 * pi 的原文注释即定位：<i>"Hosts own the scheduling; this module owns the numbers."</i>
 *
 * <p>术语：<b>cache warming（缓存预热）</b>——provider 的 prompt 缓存有 TTL
 * （Anthropic 默认 5 分钟）；TTL 到期前用一个 {@code maxTokens=1} 的重放请求刷新
 * 缓存项，使下一次真实请求仍命中缓存（cache-read 价 ≈ 1/10 输入价）。</p>
 *
 * <p>本类只搬 pi 在 ai 库导出的那一半；{@code CacheWarmingStatus}／
 * {@code CacheWarmRequest}／{@code CacheWarmer} 调度器在 pi 就属 coding-agent
 * （{@code cache-warmer.ts:122-401}），仍留在 {@code com.pijava.coding.agent.core}。</p>
 *
 * <p>{@code null ≙ pi 的 undefined}（{@link #delayMs}、{@link #promptCacheTtlMs}）。</p>
 */
public final class CacheWarmingPolicy {

    /** pi :31——streaming 阶段安全窗：自发起请求起不超过一小时。 */
    public static final long MAX_AGE_MS = 60 * 60_000L;

    /** pi :34——idle 阶段安全窗（估计可靠性随年龄下降）。 */
    public static final long MAX_IDLE_AGE_MS = 30 * 60_000L;

    /** pi :37——期望节省下限（美元）。 */
    public static final double MINIMUM_EXPECTED_SAVINGS = 0.05;

    /** pi :43——idle 期间真实请求先到的概率（pi 实测常量，每会话估计并不更优）。 */
    public static final double IDLE_CONTINUATION_PROBABILITY = 0.15;

    /** pi :78——一次重放的请求覆盖：{@code maxTokens=1}、不续接 provider thread 状态、不重试。 */
    public static final WarmRequestOptions WARM_REQUEST_OPTIONS =
        new WarmRequestOptions(1, true, 0);

    private CacheWarmingPolicy() {}

    /**
     * pi :8 的 {@code CacheWarmingAction}——纯常量闭集 ⇒ enum（CLAUDE.md 规范）。
     */
    public enum Action {
        /** 发出本次暖刷新。 */
        WARM,
        /** 不刷新；结束本轮保温直到下一个真实请求。 */
        STOP;

        /** pi 的线格字面量（{@code "warm"}/{@code "stop"}）。 */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * pi :11 的 {@code CacheWarmingPhase}——发起请求的那个 agent run 是否还活着。
     */
    public enum Phase {
        /** 发出请求的 agent run 仍在跑。 */
        STREAMING,
        /** run 已落定，等待下一次真实请求。 */
        IDLE;

        /** pi 的线格字面量（{@code "streaming"}/{@code "idle"}）。 */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * pi :14-29 的 {@code CacheWarmingDecision}——一次决策的输入与结局
     * （{@code /session} 展示面）。
     *
     * @param phase                   {@link Phase#STREAMING}（发起请求的那个 agent run 还活着）| {@link Phase#IDLE}
     * @param warmCost                本次刷新的价格：prompt 的 cache-read ＋ 1 个 output token
     * @param missCost                缓存项丢失后下一个真实请求多付的价格
     * @param continuationProbability 缓存到期前有真实请求到达的估计概率
     * @param expectedSavings         {@code continuationProbability * missCost - warmCost}
     * @param economicsAvailable      false＝prompt 量或模型价目未知
     * @param action                  pi 的决策：期望节省 ≥ $0.05 ⇒ {@link Action#WARM}
     */
    public record Decision(Phase phase,
                           double warmCost, double missCost, double continuationProbability,
                           double expectedSavings, boolean economicsAvailable, Action action) {}

    /**
     * pi :78-81 的 {@code getCacheWarmingRequestOptions} 在 java 的**无 signal 形**
     * （裁决 R3）：java 没有统一的 options record，abort 由宿主
     * {@code WarmAbort} 承载（D65），故只导出三项覆盖值，由宿主分别落到
     * {@code StreamOptions}／{@code ApiOptions.extra}／{@code ApiOptions.maxRetries}。
     *
     * @param maxTokens            pi 的 {@code maxTokens: 1}
     * @param disableContinuation  pi 的 {@code disableContinuation: true}
     * @param maxRetries           pi 的 {@code maxRetries: 0}
     */
    public record WarmRequestOptions(int maxTokens, boolean disableContinuation, int maxRetries) {}

    /**
     * pi :46-49——TTL 的 90% 且至少留 10 秒余量；TTL ≤ 10s 不 warm。
     *
     * @return 延迟毫秒，或 {@code null}（≙ pi 的 {@code undefined}）
     */
    public static Long delayMs(long ttlMs) {
        if (ttlMs <= 10_000) {
            return null;
        }
        return Math.max(1, (long) Math.floor(Math.min(ttlMs * 0.9, ttlMs - 10_000)));
    }

    /**
     * pi :55-66——请求所写缓存项的寿命：按 retention 取模型的 {@code promptCache} 档。
     * 模型该档缺席或关闭缓存 ⇒ {@code null}。
     *
     * @param requested    请求期选项（{@code Optional.empty()} ≙ pi 的 {@code undefined}）
     * @param envRetention {@code PI_CACHE_RETENTION} 的**原始**取值（{@code null} ≙ 未设）
     * @return 寿命毫秒，或 {@code null}
     */
    public static Long promptCacheTtlMs(ModelInfo model, Optional<CacheRetention> requested,
                                        String envRetention) {
        var retention = CompatResolver.resolveCacheRetention(
            requested == null ? Optional.empty() : requested, envRetention);
        if (retention == CacheRetention.NONE) {
            return null;
        }
        var lifetimes = model.promptCache();
        if (lifetimes == null) {
            return null;
        }
        var seconds = retention == CacheRetention.LONG
            ? lifetimes.longSeconds() : lifetimes.shortSeconds();
        return seconds == null ? null : seconds.longValue() * 1000L;
    }

    /**
     * pi :69-75——用 {@code maxTokens=1} 重放是否不碰缓存项。anthropic-messages 的
     * 预算思考（无 adaptive 的 Claude 模型）从 {@code max_tokens} 推
     * {@code budget_tokens}；重放会换 budget（Anthropic 以它做消息缓存键）且模型仍可能
     * 真思考数千 token ⇒ 不可重放。
     *
     * @param reasoningRequested pi {@code options?.reasoning} 的布尔投影（有值＝请求了思考）
     */
    public static boolean isReplayable(ModelInfo model, boolean reasoningRequested) {
        if (!reasoningRequested || !"anthropic-messages".equals(model.api())) {
            return true;
        }
        return model.compat().forceAdaptiveThinking();
    }

    /**
     * pi :83-107——{@code promptTokens} 的请求在 {@code phase} 里刷新缓存是否划算。
     * {@code promptTokens} 由宿主从最近一次真实请求的 usage 算出（pi 同）。
     */
    public static Decision evaluate(ModelInfo model, long promptTokens, Phase phase) {
        var pricing = model.pricing();
        var cacheHitCost = price(pricing, 0, 0, promptTokens, 0);
        // pi :85-88：有 cacheWrite 价 ⇒ miss 落在 cacheWrite 桶，否则落在 input 桶。
        var cacheMissCost = pricing.cacheWritePrice() > 0
            ? price(pricing, 0, 0, 0, promptTokens)
            : price(pricing, promptTokens, 0, 0, 0);
        var warmCost = price(pricing, 0, 1, promptTokens, 0);
        var missCost = Math.max(0, cacheMissCost - cacheHitCost);
        var continuationProbability = phase == Phase.IDLE
            ? IDLE_CONTINUATION_PROBABILITY : 1;
        var economicsAvailable = promptTokens > 0 && (cacheHitCost > 0 || cacheMissCost > 0);
        var expectedSavings = continuationProbability * missCost - warmCost;
        return new Decision(phase, warmCost, missCost,
            continuationProbability, expectedSavings, economicsAvailable,
            expectedSavings >= MINIMUM_EXPECTED_SAVINGS ? Action.WARM : Action.STOP);
    }

    /** pi :109-121：按目录价目给 token 组合计价（{@link CostCalculator} 逐行移植对位）。 */
    private static double price(PricingInfo pricing, double input, double output,
                                double cacheRead, double cacheWrite) {
        var usage = new Usage(input, output, cacheRead, cacheWrite, null, null, 0,
            Usage.Cost.zero());
        return CostCalculator.calculateCost(pricing, usage).total();
    }
}
