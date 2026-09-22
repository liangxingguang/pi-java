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

    /** pi {@code simple-options.ts:55} {@code MIN_ANSWER_TOKENS}。 */
    public static final int MIN_ANSWER_TOKENS = 1024;

    /**
     * pi {@code simple-options.ts:68-72} {@code thinkingBudgetForLevel}。
     *
     * <pre>{@code
     * const budgets = { ...DEFAULT_THINKING_BUDGETS, ...customBudgets };
     * const level = clampReasoning(reasoningLevel)!;
     * return budgets[level]!;
     * }</pre>
     *
     * <p>⚠️ pi 的 {@code clampReasoning}（{@code :64-66}）把 {@code xhigh}/{@code max}
     * 先夹到 {@code high} —— 因为 {@link ThinkingBudgets} <b>只有 4 级</b>。
     * 本方法把这个夹取写进 switch 的臂里（{@code High}/{@code XHigh}/{@code Max} 同臂）。</p>
     *
     * <p>pi 的 {@code {...DEFAULT, ...custom}} 是<b>浅合并</b> ⇒ 自定义值<b>逐级</b>覆盖，
     * 未给的那几级仍取默认。</p>
     */
    public int budgetFor(ThinkingLevel level) {
        return switch (level) {
            case ThinkingLevel.Minimal() -> pick(minimal, DEFAULT.minimal());
            case ThinkingLevel.Low() -> pick(low, DEFAULT.low());
            case ThinkingLevel.Medium() -> pick(medium, DEFAULT.medium());
            case ThinkingLevel.High(), ThinkingLevel.XHigh(), ThinkingLevel.Max()
                -> pick(high, DEFAULT.high());
        };
    }

    /**
     * pi {@code simple-options.ts:79-94} {@code adjustMaxTokensForThinking}。
     *
     * <pre>{@code
     * let thinkingBudget = thinkingBudgetForLevel(reasoningLevel, customBudgets);
     * const maxTokens = baseMaxTokens === undefined
     *     ? modelMaxTokens
     *     : Math.min(baseMaxTokens + thinkingBudget, modelMaxTokens);
     * if (maxTokens <= thinkingBudget) {
     *     thinkingBudget = clampThinkingBudgetToAnswerRoom(thinkingBudget, maxTokens);
     * }
     * }</pre>
     *
     * <p>pi 的注释（{@code :889}）：{@code undefined} 表示调用方没给输出上限 ⇒
     * <b>用模型上限，且不把预算加进去</b>。</p>
     *
     * @param baseMaxTokens 调用方给的输出上限；空 ≙ pi 的 {@code undefined}
     * @param modelMaxTokens 模型自身的输出上限（pi 的 {@code model.maxTokens}）
     */
    public static Adjusted adjust(OptionalInt baseMaxTokens, int modelMaxTokens,
                                  ThinkingLevel level, ThinkingBudgets custom) {
        int thinkingBudget = custom.budgetFor(level);
        int maxTokens = baseMaxTokens.isEmpty()
            ? modelMaxTokens
            : Math.min(baseMaxTokens.getAsInt() + thinkingBudget, modelMaxTokens);
        if (maxTokens <= thinkingBudget) {
            thinkingBudget = clampToAnswerRoom(thinkingBudget, maxTokens);
        }
        return new Adjusted(maxTokens, thinkingBudget);
    }

    /** pi {@code simple-options.ts:75-77} {@code clampThinkingBudgetToAnswerRoom}。 */
    private static int clampToAnswerRoom(int thinkingBudget, int ceiling) {
        return Math.min(thinkingBudget, Math.max(0, ceiling - MIN_ANSWER_TOKENS));
    }

    /** pi 的 {@code budgets[level]!} —— 合并后总是有值，故此处取自定义优先、否则默认。 */
    private static int pick(OptionalInt custom, OptionalInt fallback) {
        return custom.orElseGet(fallback::getAsInt);
    }

    /** pi 的 {@code { maxTokens; thinkingBudget }}。 */
    public record Adjusted(int maxTokens, int thinkingBudget) {}
}
