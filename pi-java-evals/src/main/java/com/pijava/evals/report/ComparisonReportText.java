package com.pijava.evals.report;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * L6 W1/W2（docs/26 §2.3/§3.3）：配对报告的文本渲染（{@code report.txt}）——
 * pi {@code formatEvalComparisonReport}（{@code report.ts:437-483}）对位。
 *
 * <p>段序逐项对位：每个 eval set 先 {@code Pairs} → {@code Pass rate} →
 * {@code Flags} → {@code Tokens} → {@code Tools} → {@code Latency} → {@code Est. cost}，
 * 之后是全局 {@code Operational totals} 与 {@code Blocked pairs}。</p>
 *
 * <p>不逐字抄 pi 的 ANSI 加粗（{@code styleText("bold", ...)}）：报告落盘为纯文本，
 * 着色只在终端有意义。这是既有的文本级方言（D185 族），语义不受影响。</p>
 */
public final class ComparisonReportText {

    private ComparisonReportText() {
    }

    /**
     * Render a report as deterministic plain text.
     *
     * @param report report to render
     * @return text ending with a newline
     */
    public static String render(ComparisonReport report) {
        if (report.comparisons().isEmpty()) {
            return "eval comparison: no comparisons\n";
        }
        var text = new StringBuilder("Documentation Eval Comparisons\n");
        for (var comparison : report.comparisons()) {
            appendComparison(text, comparison);
        }
        text.append("  Operational totals\n");
        for (var totals : report.variantTotals()) {
            appendVariantTotals(text, totals);
        }
        if (!report.blockedPairs().isEmpty()) {
            text.append("  Blocked pairs\n");
            for (var blocked : report.blockedPairs()) {
                text.append("    ").append(blocked.evalSet()).append('/')
                    .append(blocked.caseName()).append("/run-").append(blocked.run())
                    .append(": ").append(String.join("; ", blocked.reasons())).append('\n');
            }
        }
        return text.toString();
    }

    private static void appendComparison(StringBuilder text, EvalSetComparison comparison) {
        text.append("  ").append(comparison.evalSet()).append('\n');
        text.append("         Pairs  ").append(comparison.eligiblePairs()).append('/')
            .append(comparison.totalPairs()).append(" eligible\n");
        if (comparison.lift() == null) {
            text.append(comparison.blockedPairs() > 0
                ? "     Pass rate  withheld because pairs are blocked\n"
                : "     Pass rate  unavailable\n");
        } else {
            text.append("     Pass rate  ")
                .append(signed(comparison.lift() * 100.0, 1)).append(" pp (with ")
                .append(percentage(comparison.treatmentPassRate())).append(", without ")
                .append(percentage(comparison.controlPassRate())).append(")\n");
        }
        if (!comparison.flags().isEmpty()) {
            text.append("         Flags  ").append(String.join(", ", comparison.flags()))
                .append('\n');
        }
        appendPaired(text, "Tokens", comparison.totalTokens(), "");
        appendPaired(text, "Tools", comparison.toolCalls(), "");
        appendPaired(text, "Latency", comparison.totalMs(), "ms");
        appendCost(text, comparison.estimatedCostUsd());
    }

    private static void appendPaired(StringBuilder text, String label,
                                     PairedMetricSummary metric, String unit) {
        if (metric.unavailable()) {
            text.append("    ").append(pad(label)).append("  unavailable\n");
            return;
        }
        text.append("    ").append(pad(label)).append("  ")
            .append(signed(metric.meanDelta(), 1)).append(unit)
            .append(" (with ").append(fixed(metric.treatmentMean(), 1)).append(unit)
            .append(", without ").append(fixed(metric.controlMean(), 1)).append(unit)
            .append(", ").append(metric.eligiblePairs()).append(" pairs)\n");
    }

    private static void appendCost(StringBuilder text, PairedMetricSummary metric) {
        if (metric.unavailable()) {
            text.append("     Est. cost  unavailable\n");
            return;
        }
        text.append("     Est. cost  ").append(metric.meanDelta() >= 0 ? "+" : "-")
            .append('$').append(fixed(Math.abs(metric.meanDelta()), 4))
            .append(" (with $").append(fixed(metric.treatmentMean(), 4))
            .append(", without $").append(fixed(metric.controlMean(), 4))
            .append(", ").append(metric.eligiblePairs()).append(" pairs)\n");
    }

    private static void appendVariantTotals(StringBuilder text, VariantTotals totals) {
        text.append("    ").append(totals.variant().wireName()).append(": ")
            .append(totals.runs()).append(" runs, ")
            .append(operational(totals.totalTokens(), totals.runs(), value -> value + " tokens"))
            .append(", ")
            .append(operational(totals.toolCalls(), totals.runs(), value -> value + " tools"))
            .append(", ")
            .append(operational(totals.totalMs(), totals.runs(),
                value -> String.format(Locale.ROOT, "%.2fs", value / 1000.0)))
            .append(", ")
            .append(operational(totals.estimatedCostUsd(), totals.runs(),
                value -> String.format(Locale.ROOT, "$%.4f cost", value)))
            .append('\n');
    }

    /** pi {@code operationalMetric}（{@code report.ts:431-435}）：带覆盖度。 */
    private static String operational(OperationalMetricTotal metric, long runs,
                                      java.util.function.DoubleFunction<String> format) {
        if (metric.total() == null) {
            return "unavailable (0/" + runs + " measured)";
        }
        var coverage = metric.availableRuns() == runs
            ? "" : " (" + metric.availableRuns() + "/" + runs + " measured)";
        return format.apply(metric.total()) + coverage;
    }

    /** 状态计数（诊断面，pi 把逐 case 结果留在 workspace 里）。 */
    static String statusCounts(ComparisonReport report) {
        return report.statusCounts().entrySet().stream()
            .map(entry -> entry.getKey().wireName() + "=" + entry.getValue())
            .collect(Collectors.joining(", "));
    }

    private static String percentage(Double value) {
        return value == null ? "unavailable"
            : String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private static String signed(double value, int digits) {
        return (value >= 0 ? "+" : "-") + fixed(Math.abs(value), digits);
    }

    /** Java 的 {@link java.util.Formatter} 不支持 C 的 {@code *} 精度占位，故拼格式串。 */
    private static String fixed(Double value, int digits) {
        return value == null ? "unavailable"
            : String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    private static String pad(String label) {
        return " ".repeat(Math.max(0, 10 - label.length())) + label;
    }
}
