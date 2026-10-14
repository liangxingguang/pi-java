package com.pijava.agent.harness;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.thinking.ThinkingBudgets;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * Extra options passed to {@link StreamFn} on each LLM call.
 *
 * <p>Aligned with pi's {@code SimpleStreamOptions}（{@code packages/ai/src/types.ts:324-336}）
 * plus the limits inherited from {@code StreamOptions}（{@code :179-199}）。
 * pi 的 options 类型里**没有 tools** —— 工具定义属于 {@link Context}，见该类注释。</p>
 *
 * <p>⚠️ <b>包H5 的改动</b>：此前这里装的是<b>已翻译</b>的 {@code ThinkingConfig}（那是一个
 * java 发明、pi 没有的类型）。pi 的 {@code SimpleStreamOptions} 装的是<b>未翻译</b>的
 * {@code reasoning?: ThinkingLevel}，<b>翻译在车道内做</b>（{@code anthropic-messages.ts:858-904}）
 * —— 因为翻译需要 {@code model.compat} 与 {@code model.thinkingLevelMap}，而引擎层两者都拿不到。</p>
 *
 * <p>B128：pi 的 {@code thinkingBudgets} 随 {@code AgentLoopConfig extends
 * SimpleStreamOptions} 在 bin pi 主流（Agent/agent-loop {@code ...config} 展开）
 * <b>原样转发</b>。先前「harness 路径不转发」的结论引用的
 * {@code harness/execution/assistant.ts} 在锚点 {@code ea448f454} 已随整个
 * {@code harness/} 目录删除（{@code git ls-tree} 为空）⇒ 旧结论作废，通道照此接通。
 * {@code null} ≙ pi 的 {@code undefined}，消费边界按
 * {@link ThinkingBudgets#DEFAULT} 浅合并。</p>
 *
 * @param maxTokens    max output tokens (empty = use model default)
 * @param temperature  sampling temperature (empty = use model default)
 * @param reasoning    pi {@code SimpleStreamOptions.reasoning}；<b>空 = 不开思考</b>
 *                     （pi 的 {@code "off"} 在请求侧就是「不传」，见 {@code agent.ts:465}）
 * @param cacheRetention pi {@code SimpleStreamOptions.cacheRetention}（包 A-01）；空 = 用
 *                     pi 的缺省链（{@code PI_CACHE_RETENTION} ?? {@code "short"}）。
 * @param sessionId    pi {@code SimpleStreamOptions.sessionId}（包 B103）：会话亲和头/
 *                     {@code prompt_cache_key} 的来源。空 ≙ pi 的 {@code undefined}。
 *                     ⚠️ 压缩/摘要路径保持<b>空</b>（pi 在该路径显式传 routing id、
 *                     不是主会话 id；{@code 原 docs/61 §1.5}）。{@code env} 仍不带
 *                     （{@code 原 docs/54 §3 F2}）。
 * @param thinkingBudgets B128：pi {@code SimpleStreamOptions.thinkingBudgets}（settings.json
 *                     四段）；{@code null} ≙ {@code undefined} ⇒ 默认表。摘要路径传
 *                     {@code null}（pi 的摘要调用不带用户 budget 表）。
 */
public record StreamOptions(
    OptionalInt maxTokens,
    OptionalDouble temperature,
    Optional<ThinkingLevel> reasoning,
    Optional<CacheRetention> cacheRetention,
    Optional<String> sessionId,
    ThinkingBudgets thinkingBudgets
) {
    /**
     * 五参便捷构造（B128 之前的**规范**构造）：{@code thinkingBudgets} 缺席 ≙ pi 的
     * {@code undefined}。保留该形态使 B128 之前的构造点**零改签**。
     */
    public StreamOptions(OptionalInt maxTokens,
                         OptionalDouble temperature,
                         Optional<ThinkingLevel> reasoning,
                         Optional<CacheRetention> cacheRetention,
                         Optional<String> sessionId) {
        this(maxTokens, temperature, reasoning, cacheRetention, sessionId, null);
    }

    /**
     * 四参便捷构造（包 B103 之前的**规范**构造）：{@code sessionId} 缺席 ≙ pi 的
     * {@code undefined}。保留该形态使包 B103 之前的构造点**零改签**。
     */
    public StreamOptions(OptionalInt maxTokens,
                         OptionalDouble temperature,
                         Optional<ThinkingLevel> reasoning,
                         Optional<CacheRetention> cacheRetention) {
        this(maxTokens, temperature, reasoning, cacheRetention, Optional.empty(), null);
    }

    /**
     * 三参便捷构造（包 A-01 之前的**规范**构造）：{@code cacheRetention} 缺席 ≙ pi 的
     * {@code undefined} ⇒ 走缺省链。保留该形态使包 A-01 之前的三个构造点**零改签**。
     */
    public StreamOptions(OptionalInt maxTokens,
                         OptionalDouble temperature,
                         Optional<ThinkingLevel> reasoning) {
        this(maxTokens, temperature, reasoning, Optional.empty(), Optional.empty(), null);
    }

    /** Default options: no max tokens, no temperature, no thinking, default cache retention. */
    public static StreamOptions defaults() {
        return new StreamOptions(
            OptionalInt.empty(),
            OptionalDouble.empty(),
            Optional.empty()
        );
    }
}
