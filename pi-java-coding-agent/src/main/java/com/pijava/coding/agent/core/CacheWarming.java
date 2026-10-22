package com.pijava.coding.agent.core;

/**
 * 批 13（docs/24 T1）：pi {@code cache-warmer.ts} 的类型族（:88-155）——
 * 密封族单文件（{@link AgentSessionEvent} 同格）。{@code null ≙ pi 的 undefined}。
 *
 * <p>术语：<b>cache warming（缓存预热）</b>——provider 的 prompt 缓存有 TTL
 * （Anthropic 默认 5 分钟）；TTL 到期前用一个 {@code maxTokens=1} 的重放请求刷新
 * 缓存项，使下一次真实请求仍命中缓存（cache-read 价 ≈ 1/10 输入价）。</p>
 */
public final class CacheWarming {

    private CacheWarming() {}

    /** pi :88 的 {@code CacheWarmingAction}——纯常量闭集 ⇒ enum（CLAUDE.md 规范）。 */
    public enum Action {
        /** 发出本次暖刷新。 */
        WARM,
        /** 不刷新；结束本轮保温直到下一个真实请求。 */
        STOP;

        /** pi 的线格字面量（{@code "warm"}/{@code "stop"}）。 */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * pi :91-106 的 {@code CacheWarmingDecision}——一次决策的输入与结局
     * （{@code /session} 展示面）。
     *
     * @param phase                   "streaming"（发起请求的那个 agent run 还活着）| "idle"
     * @param warmCost                本次刷新的价格：prompt 的 cache-read ＋ 1 个 output token
     * @param missCost                缓存项丢失后下一个真实请求多付的价格
     * @param continuationProbability 缓存到期前有真实请求到达的估计概率
     * @param expectedSavings         {@code continuationProbability * missCost - warmCost}
     * @param economicsAvailable      false＝prompt 量或模型价目未知
     * @param action                  pi 的决策：期望节省 ≥ $0.05 ⇒ WARM
     */
    public record Decision(String phase,
                           double warmCost, double missCost, double continuationProbability,
                           double expectedSavings, boolean economicsAvailable, Action action) {}

    /**
     * pi :122-132 的 {@code CacheWarmingStatus}——warmer 状态读口。
     *
     * @param state             "inactive" | "scheduled"（刷新计时器在走）| "refreshing"（暖请求在飞）
     * @param reason            没有排期的理由（停止理由全集见 {@link CacheWarmer} 类注释）
     * @param nextWarmAt        下次刷新的 epoch ms
     * @param decision          待决的决策，或让保温停下的那次决策
     * @param extensionOverride true＝扩展改写过 {@code decision.action}
     */
    public record Status(String state,
                         String reason, Long nextWarmAt, Decision decision,
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
