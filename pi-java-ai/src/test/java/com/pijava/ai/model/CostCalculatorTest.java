package com.pijava.ai.model;

import java.util.List;

import com.pijava.ai.Usage;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * pi {@code packages/ai/src/models.ts:900-920} {@code calculateCost} 的逐条移植哨兵
 * （包 H1 步 1，{@code docs/42 §8.3 T1}）。
 *
 * <p>覆盖四件事：① 四费率 × 四分量的基本换算；② <b>1h 缓存按 2× 输入价</b>；
 * ③ {@code tiers} 的四个边界（{@code ==} 不命中 / {@code >} 命中 / 取最高档 / 整单适用）；
 * ④ 阶梯判据量只含 {@code input + cacheRead + cacheWrite}（<b>不含 output</b>）。
 */
class CostCalculatorTest {

    /** 一个费率齐全的价目：input 3 / output 15 / cacheRead 0.3 / cacheWrite 3.75（$/1M）。 */
    private static PricingInfo priced() {
        return new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of());
    }

    private static Usage usage(double input, double output, double cacheRead, double cacheWrite,
                               Double cacheWrite1h) {
        return new Usage(input, output, cacheRead, cacheWrite, cacheWrite1h, null,
            input + output + cacheRead + cacheWrite, Usage.Cost.zero());
    }

    // ═══════════════════════════════════════════════════════════
    // ① 四费率 × 四分量
    // ═══════════════════════════════════════════════════════════

    @Test
    void eachComponentIsPricedAtItsOwnRate() {
        var cost = CostCalculator.calculateCost(priced(),
            usage(1_000_000, 1_000_000, 1_000_000, 1_000_000, null));

        assertThat(cost.input()).isEqualTo(3.0);
        assertThat(cost.output()).isEqualTo(15.0);
        assertThat(cost.cacheRead()).isCloseTo(0.3, within(1e-9));
        assertThat(cost.cacheWrite()).isCloseTo(3.75, within(1e-9));
        assertThat(cost.total()).isCloseTo(3.0 + 15.0 + 0.3 + 3.75, within(1e-9));
    }

    @Test
    void totalIsTheSumOfTheFourComponents() {
        var cost = CostCalculator.calculateCost(priced(), usage(2_000, 500, 100, 50, null));

        assertThat(cost.total())
            .isCloseTo(cost.input() + cost.output() + cost.cacheRead() + cost.cacheWrite(), within(1e-12));
    }

    // ═══════════════════════════════════════════════════════════
    // ② 1h 缓存按 2× 输入价（pi models.ts:911-917）
    // ═══════════════════════════════════════════════════════════

    @Test
    void oneHourCacheWritesArePricedAtTwiceTheInputRate() {
        // 全部 1M 都是 1h：longWrite = 1M，shortWrite = 0
        // ⇒ cacheWrite 成本 = (3.75 * 0 + 3.0 * 2 * 1M) / 1M = 6.0
        var cost = CostCalculator.calculateCost(priced(),
            usage(0, 0, 0, 1_000_000, 1_000_000.0));

        assertThat(cost.cacheWrite()).isCloseTo(6.0, within(1e-9));
    }

    @Test
    void oneHourAndFiveMinuteCacheWritesArePricedSeparately() {
        // 1M 总写入，其中 400k 是 1h、600k 是 5m
        // ⇒ (3.75 * 600_000 + 3.0 * 2 * 400_000) / 1M = 2.25 + 2.4 = 4.65
        var cost = CostCalculator.calculateCost(priced(),
            usage(0, 0, 0, 1_000_000, 400_000.0));

        assertThat(cost.cacheWrite()).isCloseTo(4.65, within(1e-9));
    }

    @Test
    void absentOneHourSplitPricesEveryWriteAtTheFiveMinuteRate() {
        // pi: `const longWrite = usage.cacheWrite1h ?? 0`
        var cost = CostCalculator.calculateCost(priced(), usage(0, 0, 0, 1_000_000, null));

        assertThat(cost.cacheWrite()).isCloseTo(3.75, within(1e-9));
    }

    // ═══════════════════════════════════════════════════════════
    // ③ tiers 的四个边界
    // ═══════════════════════════════════════════════════════════

    private static PricingInfo tiered() {
        return new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of(
            new PricingInfo.CostTier(100_000, 6.0, 22.5, 0.6, 7.5)));
    }

    @Test
    void thresholdIsStrictlyGreaterThanSoEqualityDoesNotMatch() {
        // pi: `inputTokens > tier.inputTokensAbove`（models.ts:905）—— 严格 >
        var cost = CostCalculator.calculateCost(tiered(), usage(100_000, 0, 0, 0, null));

        assertThat(cost.input()).isCloseTo(0.3, within(1e-9)); // 基础档 3.0/1M × 100k
    }

    @Test
    void oneTokenOverTheThresholdMatchesTheTier() {
        var cost = CostCalculator.calculateCost(tiered(), usage(100_001, 0, 0, 0, null));

        assertThat(cost.input()).isCloseTo(6.0 * 100_001 / 1_000_000, within(1e-9));
    }

    @Test
    void theHighestMatchingThresholdWinsRegardlessOfDeclarationOrder() {
        // 声明顺序颠倒：高档在前、低档在后 —— 仍应取阈值最高的命中档
        var pricing = new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of(
            new PricingInfo.CostTier(500_000, 12.0, 45.0, 1.2, 15.0),
            new PricingInfo.CostTier(100_000, 6.0, 22.5, 0.6, 7.5)));

        var cost = CostCalculator.calculateCost(pricing, usage(600_000, 0, 0, 0, null));

        assertThat(cost.input()).isCloseTo(12.0 * 600_000 / 1_000_000, within(1e-9));
    }

    @Test
    void theMatchedTierAppliesToTheWholeRequestNotJustTheExcess() {
        // pi types.ts:954 逐字：「The highest matching input threshold applies to the full request.」
        var cost = CostCalculator.calculateCost(tiered(), usage(200_000, 1_000, 5_000, 2_000, null));

        // 全部 input/output/cacheRead/cacheWrite 都走高档费率
        assertThat(cost.input()).isCloseTo(6.0 * 200_000 / 1_000_000, within(1e-9));
        assertThat(cost.output()).isCloseTo(22.5 * 1_000 / 1_000_000, within(1e-9));
        assertThat(cost.cacheRead()).isCloseTo(0.6 * 5_000 / 1_000_000, within(1e-9));
        assertThat(cost.cacheWrite()).isCloseTo(7.5 * 2_000 / 1_000_000, within(1e-9));
    }

    // ═══════════════════════════════════════════════════════════
    // ④ 阶梯判据量不含 output（pi models.ts:901）
    // ═══════════════════════════════════════════════════════════

    @Test
    void outputTokensDoNotCountTowardTheTierThreshold() {
        // input 只有 1k，但 output 高达 1M —— 若判据含 output 就会命中高档
        var cost = CostCalculator.calculateCost(tiered(), usage(1_000, 1_000_000, 0, 0, null));

        assertThat(cost.input()).isCloseTo(3.0 * 1_000 / 1_000_000, within(1e-9)); // 基础档
        assertThat(cost.output()).isCloseTo(15.0 * 1_000_000 / 1_000_000, within(1e-9));
    }

    @Test
    void cacheTokensDoCountTowardTheTierThreshold() {
        // input 1k + cacheRead 200k ⇒ 判据量 201k > 100k ⇒ 命中高档
        var cost = CostCalculator.calculateCost(tiered(), usage(1_000, 0, 200_000, 0, null));

        assertThat(cost.input()).isCloseTo(6.0 * 1_000 / 1_000_000, within(1e-9));
        assertThat(cost.cacheRead()).isCloseTo(0.6 * 200_000 / 1_000_000, within(1e-9));
    }

    // ═══════════════════════════════════════════════════════════
    // 未知费率的语义（裁决 B：未知按 0 计价，与 pi 缺数据落 0 同行为）
    // ═══════════════════════════════════════════════════════════

    @Test
    void unknownRatesPriceAtZero() {
        var cost = CostCalculator.calculateCost(PricingInfo.UNKNOWN, usage(1_000, 2_000, 3_000, 4_000, null));

        assertThat(cost.total()).isZero();
    }

    @Test
    void unknownCacheRatesStillPriceInputAndOutput() {
        // 旧 2 参形状 ⇒ cache 费率未知；input/output 仍应有值
        var cost = CostCalculator.calculateCost(new PricingInfo(3.0, 15.0),
            usage(1_000, 2_000, 500, 500, null));

        assertThat(cost.input()).isCloseTo(3.0 * 1_000 / 1_000_000, within(1e-9));
        assertThat(cost.output()).isCloseTo(15.0 * 2_000 / 1_000_000, within(1e-9));
        assertThat(cost.cacheRead()).isZero();
        assertThat(cost.cacheWrite()).isZero();
    }

    // ═══════════════════════════════════════════════════════════
    // 接线：Usage.withCost 写回
    // ═══════════════════════════════════════════════════════════

    @Test
    void withCostKeepsEveryTokenFieldAndReplacesOnlyTheCost() {
        var original = usage(1_000, 2_000, 300, 400, 100.0);
        var priced = original.withCost(CostCalculator.calculateCost(priced(), original));

        assertThat(priced.input()).isEqualTo(original.input());
        assertThat(priced.output()).isEqualTo(original.output());
        assertThat(priced.cacheRead()).isEqualTo(original.cacheRead());
        assertThat(priced.cacheWrite()).isEqualTo(original.cacheWrite());
        assertThat(priced.cacheWrite1h()).isEqualTo(original.cacheWrite1h());
        assertThat(priced.reasoning()).isEqualTo(original.reasoning());
        assertThat(priced.totalTokens()).isEqualTo(original.totalTokens());
        assertThat(priced.cost().total()).isGreaterThan(0);
    }
}
