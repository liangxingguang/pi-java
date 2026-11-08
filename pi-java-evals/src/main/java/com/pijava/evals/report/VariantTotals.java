package com.pijava.evals.report;

import java.util.List;
import java.util.Objects;

import com.pijava.evals.harness.HarnessUsage;
import com.pijava.evals.plan.DocumentationVariant;

/**
 * L6 W2（docs/26 §3）：单变体的运行总量 —— pi {@code VariantTotals}
 * （{@code report.ts:62-73}）对位。
 *
 * <p>{@link #runs()} 是该变体实际观测到的 run 数；每个 {@link OperationalMetricTotal}
 * 自带 {@code availableRuns}，两者不等即表示该指标有 run 缺席，文本报告会印
 * {@code (1/2 measured)} 这类覆盖度（pi {@code operationalMetric}
 * {@code report.ts:431-435}）。</p>
 *
 * @param variant          变体
 * @param runs             该变体的 run 数
 * @param inputTokens      输入 token 合计
 * @param outputTokens     输出 token 合计
 * @param cacheReadTokens  缓存读 token 合计
 * @param cacheWriteTokens 缓存写 token 合计
 * @param totalTokens      总 token 合计
 * @param toolCalls        工具调用数合计
 * @param totalMs          墙钟时间合计
 * @param estimatedCostUsd 估算成本合计
 */
public record VariantTotals(
    DocumentationVariant variant,
    long runs,
    OperationalMetricTotal inputTokens,
    OperationalMetricTotal outputTokens,
    OperationalMetricTotal cacheReadTokens,
    OperationalMetricTotal cacheWriteTokens,
    OperationalMetricTotal totalTokens,
    OperationalMetricTotal toolCalls,
    OperationalMetricTotal totalMs,
    OperationalMetricTotal estimatedCostUsd
) {

    /** Rejects nulls and a negative run count. */
    public VariantTotals {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(inputTokens, "inputTokens");
        Objects.requireNonNull(outputTokens, "outputTokens");
        Objects.requireNonNull(cacheReadTokens, "cacheReadTokens");
        Objects.requireNonNull(cacheWriteTokens, "cacheWriteTokens");
        Objects.requireNonNull(totalTokens, "totalTokens");
        Objects.requireNonNull(toolCalls, "toolCalls");
        Objects.requireNonNull(totalMs, "totalMs");
        Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
        if (runs < 0) {
            throw new IllegalArgumentException("runs must be >= 0");
        }
    }

    /**
     * Accumulate every observation of one variant.
     *
     * <p>usage 缺席的 run 不参与 token／工具／成本三项（{@link HarnessUsage} 为
     * {@code null}），但仍计入 {@link #totalMs()} 与 {@link #runs()}——延迟不依赖 usage。</p>
     *
     * @param variant      variant
     * @param observations observations of this variant
     * @return totals
     */
    public static VariantTotals of(DocumentationVariant variant,
                                   List<EvalObservation> observations) {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(observations, "observations");
        var input = new Accumulator();
        var output = new Accumulator();
        var cacheRead = new Accumulator();
        var cacheWrite = new Accumulator();
        var total = new Accumulator();
        var tools = new Accumulator();
        var latency = new Accumulator();
        var cost = new Accumulator();
        long runs = 0;
        for (var observation : observations) {
            if (observation.variant() != variant) {
                continue;
            }
            runs++;
            latency.add(observation.durationMs());
            var usage = observation.usage();
            if (usage == null) {
                continue;
            }
            input.add(usage.inputTokens());
            output.add(usage.outputTokens());
            cacheRead.add(usage.cacheReadTokens());
            cacheWrite.add(usage.cacheWriteTokens());
            total.add(usage.totalTokens());
            tools.add(usage.toolCalls());
            if (usage.estimatedCostUsd() != null) {
                cost.add(usage.estimatedCostUsd());
            }
        }
        return new VariantTotals(variant, runs, input.toTotal(), output.toTotal(),
            cacheRead.toTotal(), cacheWrite.toTotal(), total.toTotal(), tools.toTotal(),
            latency.toTotal(), cost.toTotal());
    }

    /** 一桶累加器：缺席不计入，因此 {@code availableRuns} 自然表达覆盖度。 */
    private static final class Accumulator {

        private long count;
        private double sum;

        void add(double value) {
            count++;
            sum += value;
        }

        OperationalMetricTotal toTotal() {
            return count == 0
                ? OperationalMetricTotal.empty()
                : new OperationalMetricTotal(count, sum);
        }
    }
}
