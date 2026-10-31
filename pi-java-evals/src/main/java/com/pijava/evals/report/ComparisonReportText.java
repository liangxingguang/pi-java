package com.pijava.evals.report;

import java.util.Locale;
import java.util.stream.Collectors;

import com.pijava.evals.plan.DocumentationVariant;

/**
 * 批 32（docs/44 §5.3）：配对报告的文本渲染（{@code report.txt}）。
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
        var text = new StringBuilder();
        text.append("eval comparison: ")
            .append(report.blocked() ? "blocked" : "ok").append('\n');
        if (report.blocked()) {
            text.append("headline pass rate and lift withheld\n");
        } else {
            appendScope(text, "control", report.control());
            appendScope(text, "treatment", report.treatment());
            text.append("lift: ").append(format(report.lift())).append('\n');
        }
        text.append("flags: ").append(report.flags().isEmpty()
            ? "none" : String.join(", ", report.flags())).append('\n');
        text.append("observations: ")
            .append(report.operationalTotals().entrySet().stream()
                .map(entry -> entry.getKey().wireName() + "=" + entry.getValue())
                .collect(Collectors.joining(", ")))
            .append('\n');
        text.append("total duration: ").append(report.totalDurationMs()).append(" ms\n");
        text.append("usage unavailable: ").append(report.usageUnavailable()).append('\n');
        text.append("cases:\n");
        for (var comparison : report.cases()) {
            text.append("- ").append(comparison.id())
                .append(": ").append(arm(comparison, DocumentationVariant.WITHOUT_DOCS))
                .append(" | ").append(arm(comparison, DocumentationVariant.WITH_DOCS))
                .append(" | delta=").append(format(comparison.delta()));
            if (comparison.blocked()) {
                text.append(" | blocked: ").append(comparison.reason());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static void appendScope(StringBuilder text, String label,
                                    ComparisonReport.ScopeSummary summary) {
        text.append(label).append(": passed ").append(summary.passed())
            .append('/').append(summary.arms()).append(' ');
        if (summary.passRate() == null) {
            text.append("(withheld)");
        } else {
            text.append('(')
                .append(String.format(Locale.ROOT, "%.2f%%", summary.passRate() * 100.0))
                .append(')');
        }
        text.append('\n');
    }

    private static String arm(ComparisonReport.CaseComparison comparison,
                              DocumentationVariant variant) {
        var control = variant == DocumentationVariant.WITHOUT_DOCS;
        var status = control ? comparison.controlStatus() : comparison.treatmentStatus();
        var score = control ? comparison.controlScore() : comparison.treatmentScore();
        return variant.wireName() + "=" + status.wireName() + "(" + format(score) + ")";
    }

    private static String format(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.4f", value);
    }
}
