package com.pijava.coding.agent.core;

import com.pijava.ai.api.CacheWarmingPolicy;

/**
 * 批 13（docs/24 T1）：pi {@code cache-warmer.ts} 的宿主类型族（:122-139）——
 * 密封族单文件（{@link AgentSessionEvent} 同格）。{@code null ≙ pi 的 undefined}。
 *
 * <p>docs/16（L1 #6）：策略／经济学（{@code Action}／{@code Phase}／{@code Decision}）
 * 已迁回 ai 的公开面 {@link CacheWarmingPolicy}（pi 把它们导出在
 * {@code packages/ai/src/cache-warming.ts}）；本类只留宿主状态
 * （{@code CacheWarmingStatus}／{@code CacheWarmRequest}），与 pi 的
 * {@code core/cache-warmer.ts} 归属一致。</p>
 *
 * <p>术语：<b>cache warming（缓存预热）</b>——provider 的 prompt 缓存有 TTL
 * （Anthropic 默认 5 分钟）；TTL 到期前用一个 {@code maxTokens=1} 的重放请求刷新
 * 缓存项，使下一次真实请求仍命中缓存（cache-read 价 ≈ 1/10 输入价）。</p>
 */
public final class CacheWarming {

    private CacheWarming() {}

    /**
     * pi :122-132 的 {@code CacheWarmingStatus}——warmer 状态读口。
     *
     * @param state             "inactive" | "scheduled"（刷新计时器在走）| "refreshing"（暖请求在飞）
     * @param reason            没有排期的理由（停止理由全集见 {@link CacheWarmer} 类注释）
     * @param nextWarmAt        下次刷新的 epoch ms
     * @param decision          待决的决策，或让保温停下的那次决策（{@link CacheWarmingPolicy.Decision}）
     * @param extensionOverride true＝扩展改写过 {@code decision.action}
     */
    public record Status(String state,
                         String reason, Long nextWarmAt,
                         CacheWarmingPolicy.Decision decision,
                         Boolean extensionOverride) {}

    /**
     * pi :135-139 的 {@code CacheWarmRequest}——要保温的那个请求，<b>按发出时原样</b>
     * 重放（{@code maxTokens}/{@code maxRetries}/{@code signal} 三键在重放时覆盖，见
     * {@link CacheWarmer}）。
     */
    public record WarmRequest(com.pijava.ai.model.ModelId<?> model,
                              com.pijava.agent.harness.Context context,
                              com.pijava.agent.harness.StreamOptions options) {}
}
