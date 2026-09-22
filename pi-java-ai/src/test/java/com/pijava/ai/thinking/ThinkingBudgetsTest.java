package com.pijava.ai.thinking;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步3：思考预算的<b>纯函数</b>（pi {@code simple-options.ts:55-94}）。
 *
 * <p>三个函数一条链：{@code clampReasoning} → {@code thinkingBudgetForLevel} →
 * {@code adjustMaxTokensForThinking}（内含 {@code clampThinkingBudgetToAnswerRoom}）。</p>
 *
 * <p>⚠️ {@code xhigh}/{@code max} 在<b>预算表上不存在</b>（pi 的 {@code ThinkingBudgets}
 * 只有 4 级）⇒ 先夹到 {@code high} 再查表。这是本文件最反直觉的一条。</p>
 */
class ThinkingBudgetsTest {

    private static final ThinkingBudgets D = ThinkingBudgets.DEFAULT;

    // ── budgetFor（pi simple-options.ts:64-72）───────────────────────────

    /** pi 的 {@code DEFAULT_THINKING_BUDGETS} 四个值逐个钉。 */
    @Test
    void defaultBudgetsArePiLiterals() {
        assertThat(D.budgetFor(new ThinkingLevel.Minimal())).isEqualTo(1024);
        assertThat(D.budgetFor(new ThinkingLevel.Low())).isEqualTo(2048);
        assertThat(D.budgetFor(new ThinkingLevel.Medium())).isEqualTo(8192);
        assertThat(D.budgetFor(new ThinkingLevel.High())).isEqualTo(16384);
    }

    /** ⚠️ {@code xhigh}/{@code max} **没有自己的预算** —— pi 的 {@code clampReasoning} 把它们夹到 {@code high}。 */
    @Test
    void xhighAndMaxFallBackToTheHighBudget() {
        assertThat(D.budgetFor(new ThinkingLevel.XHigh())).isEqualTo(16384);
        assertThat(D.budgetFor(new ThinkingLevel.Max())).isEqualTo(16384);
    }

    /** 自定义预算**逐级覆盖**默认（pi 的 {@code {...DEFAULT, ...custom}} 是浅合并）。 */
    @Test
    void customBudgetsOverridePerLevelOnly() {
        var custom = new ThinkingBudgets(OptionalInt.empty(), OptionalInt.of(7),
            OptionalInt.empty(), OptionalInt.empty());

        assertThat(custom.budgetFor(new ThinkingLevel.Low())).isEqualTo(7);
        assertThat(custom.budgetFor(new ThinkingLevel.Minimal())).isEqualTo(1024);   // 未被覆盖
        assertThat(custom.budgetFor(new ThinkingLevel.High())).isEqualTo(16384);     // 未被覆盖
    }

    // ── adjust（pi simple-options.ts:79-94）─────────────────────────────

    /** pi 的注释：{@code baseMaxTokens === undefined} ⇒ 用**模型上限**，不加上预算。 */
    @Test
    void absentBaseUsesTheModelCapVerbatim() {
        var adjusted = ThinkingBudgets.adjust(
            OptionalInt.empty(), 4096, new ThinkingLevel.Low(), D);

        assertThat(adjusted.maxTokens()).isEqualTo(4096);
        assertThat(adjusted.thinkingBudget()).isEqualTo(2048);
    }

    /** 给了 base ⇒ {@code min(base + budget, modelMax)}。 */
    @Test
    void explicitBaseGrowsByTheBudgetUpToTheModelCap() {
        var adjusted = ThinkingBudgets.adjust(
            OptionalInt.of(1000), 100_000, new ThinkingLevel.Medium(), D);

        assertThat(adjusted.maxTokens()).isEqualTo(1000 + 8192);
        assertThat(adjusted.thinkingBudget()).isEqualTo(8192);
    }

    /** 模型上限压住增长（{@code min} 那一半）。 */
    @Test
    void modelCapClampsTheGrownCeiling() {
        var adjusted = ThinkingBudgets.adjust(
            OptionalInt.of(4000), 5000, new ThinkingLevel.Medium(), D);

        assertThat(adjusted.maxTokens()).isEqualTo(5000);
        // maxTokens(5000) <= budget(8192) ⇒ 收缩到 5000 - 1024
        assertThat(adjusted.thinkingBudget()).isEqualTo(5000 - 1024);
    }

    /**
     * ⚠️ <b>收缩分支</b>（{@code maxTokens <= thinkingBudget}）：预算必须给答案留
     * {@code MIN_ANSWER_TOKENS}（1024）。
     */
    @Test
    void budgetShrinksWhenItWouldEatTheWholeCeiling() {
        var adjusted = ThinkingBudgets.adjust(
            OptionalInt.empty(), 2000, new ThinkingLevel.High(), D);

        assertThat(adjusted.maxTokens()).isEqualTo(2000);
        assertThat(adjusted.thinkingBudget()).isEqualTo(2000 - 1024);
    }

    /** 天花板低于 {@code MIN_ANSWER_TOKENS} ⇒ 预算被压到 0（pi 的 {@code Math.max(0, ...)}）。 */
    @Test
    void ceilingBelowTheAnswerRoomPinsTheBudgetToZero() {
        var adjusted = ThinkingBudgets.adjust(
            OptionalInt.empty(), 500, new ThinkingLevel.High(), D);

        assertThat(adjusted.maxTokens()).isEqualTo(500);
        assertThat(adjusted.thinkingBudget()).isZero();
    }

    /** 预算**恰好等于**天花板时也走收缩（pi 是 {@code <=}，不是 {@code <}）。 */
    @Test
    void budgetExactlyEqualToTheCeilingStillShrinks() {
        var custom = new ThinkingBudgets(
            OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(4096));

        var adjusted = ThinkingBudgets.adjust(OptionalInt.empty(), 4096, new ThinkingLevel.High(), custom);

        assertThat(adjusted.maxTokens()).isEqualTo(4096);
        assertThat(adjusted.thinkingBudget()).isEqualTo(4096 - 1024);
    }

    /** 未触发收缩时预算**原样保留**（{@code maxTokens > budget}）。 */
    @Test
    void budgetIsUntouchedWhenThereIsRoom() {
        var adjusted = ThinkingBudgets.adjust(
            OptionalInt.of(10_000), 100_000, new ThinkingLevel.Low(), D);

        assertThat(adjusted.maxTokens()).isEqualTo(10_000 + 2048);
        assertThat(adjusted.thinkingBudget()).isEqualTo(2048);
    }
}
