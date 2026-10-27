package com.pijava.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 批 27（B162 裁决⑧）：{@link Usage#combine} 对位 pi {@code combineUsage}
 * （{@code core/usage-totals.ts:31-52}）——逐字段相加；可选字段
 * （cacheWrite1h/reasoning）任一侧存在才保留。
 */
class UsageCombineTest {

    private static Usage usage(double input, double output, Double cacheWrite1h,
                               Double reasoning, double total) {
        return new Usage(input, output, 0, 0, cacheWrite1h, reasoning, total,
            new Usage.Cost(input / 1000, output / 1000, 0, 0, (input + output) / 1000));
    }

    @Test
    void sumsEveryField() {
        var sum = Usage.combine(usage(10, 5, null, null, 15), usage(4, 2, null, null, 6));

        assertThat(sum.input()).isEqualTo(14);
        assertThat(sum.output()).isEqualTo(7);
        assertThat(sum.totalTokens()).isEqualTo(21);
        assertThat(sum.cost().input()).isCloseTo(0.014, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(sum.cost().total()).isCloseTo(0.021, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void optionalSplitsKeptWhenEitherSideReportsThem() {
        var sum = Usage.combine(usage(10, 5, null, 3.0, 15), usage(4, 2, 8.0, null, 6));

        assertThat(sum.cacheWrite1h()).isEqualTo(8.0);
        assertThat(sum.reasoning()).isEqualTo(3.0);
    }

    @Test
    void optionalSplitsOmittedWhenNeitherSideReportsThem() {
        var sum = Usage.combine(usage(10, 5, null, null, 15), usage(4, 2, null, null, 6));

        assertThat(sum.cacheWrite1h()).isNull();
        assertThat(sum.reasoning()).isNull();
    }
}
