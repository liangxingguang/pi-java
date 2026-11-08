package com.pijava.evals.report;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Objects;

/**
 * L6 W1（docs/26 §2）：一组配对标量的均值与差值 —— pi
 * {@code PairedMetricSummary}（{@code report.ts:36-41}）对位。
 *
 * <p>只统计<b>两臂该指标都可用</b>的配对：任一臂缺席即整对退出本指标，
 * {@link #eligiblePairs()} 随之减一（pi {@code summarizeMetric}
 * {@code report.ts:284-305} 的 {@code continue} 臂）。缺席不得折成 0。</p>
 *
 * @param eligiblePairs  两臂都可用的配对数
 * @param controlMean    without_docs 均值，无可用配对时 {@code null}
 * @param treatmentMean  with_docs 均值，无可用配对时 {@code null}
 * @param meanDelta      {@code treatmentMean - controlMean}，任一侧为 {@code null} 时 {@code null}
 */
public record PairedMetricSummary(
    long eligiblePairs,
    Double controlMean,
    Double treatmentMean,
    Double meanDelta
) {

    /** Rejects a negative pair count. */
    public PairedMetricSummary {
        if (eligiblePairs < 0) {
            throw new IllegalArgumentException("eligiblePairs must be >= 0");
        }
    }

    /**
     * Build a summary from two equal-length value lists.
     *
     * @param control   without_docs values
     * @param treatment with_docs values
     * @return summary
     */
    public static PairedMetricSummary of(List<Double> control, List<Double> treatment) {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(treatment, "treatment");
        var controlMean = mean(control);
        var treatmentMean = mean(treatment);
        return new PairedMetricSummary(control.size(), controlMean, treatmentMean,
            controlMean == null || treatmentMean == null
                ? null : difference(treatmentMean, controlMean));
    }

    /**
     * Empty bucket.
     *
     * @return summary with zero pairs and no means
     */
    public static PairedMetricSummary empty() {
        return new PairedMetricSummary(0, null, null, null);
    }

    /** @return {@code true} when neither side has a mean yet */
    public boolean unavailable() {
        return meanDelta == null || controlMean == null || treatmentMean == null;
    }

    /**
     * Arithmetic mean, {@code null} for an empty list.
     *
     * @param values values
     * @return mean or {@code null}
     */
    static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (var value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    /**
     * pi {@code difference}（{@code report.ts:206-208}）：{@code Number((t - c).toPrecision(15))}。
     *
     * <p>用 15 位有效数字截断消除浮点尾差，使报告数字不受累加顺序影响。</p>
     *
     * @param treatment treatment value
     * @param control   control value
     * @return rounded difference
     */
    static double difference(double treatment, double control) {
        return BigDecimal.valueOf(treatment - control)
            .round(new MathContext(15))
            .doubleValue();
    }
}
