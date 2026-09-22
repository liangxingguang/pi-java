package com.pijava.ai.thinking;

import java.util.OptionalInt;

/**
 * 每级思考的 token 预算（pi {@code ThinkingBudgets}，{@code types.ts:101-107}）。
 *
 * <p>⚠️ <b>只有 4 级</b>（{@code minimal}/{@code low}/{@code medium}/{@code high}）——
 * pi 的类型里<b>没有</b> {@code xhigh}/{@code max}。pi 的 {@code clampReasoning}
 * （{@code simple-options.ts:64-66}）先把 {@code xhigh}/{@code max} 夹到 {@code high}
 * 再查表。</p>
 *
 * <p>只在<b>基于 token 的</b> provider 上有意义（Anthropic 的 budget 型思考、
 * Bedrock、OpenAI 兼容的 {@code thinking_token_budget} 类字段）。</p>
 *
 * @param minimal pi {@code ThinkingBudgets.minimal}
 * @param low     pi {@code ThinkingBudgets.low}
 * @param medium  pi {@code ThinkingBudgets.medium}
 * @param high    pi {@code ThinkingBudgets.high}
 */
public record ThinkingBudgets(
    OptionalInt minimal,
    OptionalInt low,
    OptionalInt medium,
    OptionalInt high
) {
    /** pi {@code simple-options.ts:57-62} 的 {@code DEFAULT_THINKING_BUDGETS}。 */
    public static final ThinkingBudgets DEFAULT = new ThinkingBudgets(
        OptionalInt.of(1024),
        OptionalInt.of(2048),
        OptionalInt.of(8192),
        OptionalInt.of(16384));

    /** 四级齐全的便捷构造。 */
    public static ThinkingBudgets of(int minimal, int low, int medium, int high) {
        return new ThinkingBudgets(
            OptionalInt.of(minimal), OptionalInt.of(low),
            OptionalInt.of(medium), OptionalInt.of(high));
    }
}
