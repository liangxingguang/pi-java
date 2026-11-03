package com.pijava.agent.compaction;

import com.pijava.ai.Usage;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/15（B267）：两段摘要 usage 合计的 {@code totalFromProvider} 传播 ——
 * pi {@code usage-totals.ts:51}（{@code first === true && second === true}）。
 *
 * <p>合计只有在**每一段都是 provider 实报**时才算实报；结果永远是布尔，
 * {@code false} 显式写出（不是缺席）。</p>
 */
class UsageCombinerTest {

    @Test
    void bothProviderReportedMeansTheSumIsProviderReported() {
        var out = UsageCombiner.combine(usage(Boolean.TRUE), usage(Boolean.TRUE));
        assertThat(out.cost().totalFromProvider()).isTrue();
    }

    @Test
    void oneCatalogEstimateMakesTheSumACatalogEstimate() {
        var out = UsageCombiner.combine(usage(Boolean.TRUE), usage(Boolean.FALSE));
        assertThat(out.cost().totalFromProvider()).isFalse();
    }

    @Test
    void absentFlagCountsAsCatalogEstimate() {
        var out = UsageCombiner.combine(usage(Boolean.TRUE), usage(null));
        assertThat(out.cost().totalFromProvider()).isFalse();
    }

    @Test
    void bothAbsentYieldsAnExplicitFalse() {
        var out = UsageCombiner.combine(usage(null), usage(null));
        assertThat(out.cost().totalFromProvider()).isFalse();
    }

    @Test
    void costComponentsAndTokensStillAddUp() {
        var out = UsageCombiner.combine(usage(Boolean.TRUE), usage(Boolean.TRUE));

        assertThat(out.input()).isEqualTo(20);
        assertThat(out.output()).isEqualTo(40);
        assertThat(out.totalTokens()).isEqualTo(60);
        assertThat(out.cost().total()).isCloseTo(0.02, org.assertj.core.api.Assertions.within(1e-12));
    }

    private static Usage usage(Boolean totalFromProvider) {
        return new Usage(10, 20, 0, 0, null, null, 30,
            new Usage.Cost(0.005, 0.005, 0, 0, 0.01, totalFromProvider));
    }
}
