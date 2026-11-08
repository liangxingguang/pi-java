package com.pijava.evals.report;

/**
 * L6 W2（docs/26 §3）：按变体累加的绝对量 —— pi {@code OperationalMetricTotal}
 * （{@code report.ts:60}）对位。
 *
 * <p>与 {@link PairedMetricSummary} 的分工：这是**单变体内部**的绝对合计，不做配对，
 * 因此要用 {@link #availableRuns()} 表达覆盖度——{@code 3/5 measured} 与
 * {@code 5/5 measured} 的合计不可直接比较。</p>
 *
 * @param availableRuns 该指标可用的 run 数
 * @param total         合计值，无可用 run 时 {@code null}（**不折成 0**）
 */
public record OperationalMetricTotal(long availableRuns, Double total) {

    /** Rejects a negative run count. */
    public OperationalMetricTotal {
        if (availableRuns < 0) {
            throw new IllegalArgumentException("availableRuns must be >= 0");
        }
    }

    /**
     * Empty bucket: no run contributed.
     *
     * @return total-less bucket
     */
    public static OperationalMetricTotal empty() {
        return new OperationalMetricTotal(0, null);
    }

    /** @return {@code true} when no run contributed */
    public boolean unavailable() {
        return total == null;
    }
}
