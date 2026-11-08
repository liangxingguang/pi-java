package com.pijava.evals.report;

import java.util.List;
import java.util.Map;

import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;
import com.pijava.evals.plan.TaskPlan;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** L6 W1（docs/26 §2）：配对、per-set headline、blocked 与 flags 语义。 */
class ComparisonReportTest {

    @Test
    void computesPairedLift() {
        var report = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 0.5),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITHOUT_DOCS, 2, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITH_DOCS, 2, CaseStatus.PASSED, 1.0)));
        assertThat(report.blocked()).isFalse();
        assertThat(report.withheld()).isFalse();
        assertThat(report.cases()).hasSize(2);
        assertThat(report.cases().get(0).delta()).isEqualTo(0.5);
        assertThat(report.cases().get(1).delta()).isZero();

        var comparison = report.comparisons().get(0);
        // control 两对里只有一对 score>=1，treatment 两对全过
        assertThat(comparison.controlPassRate()).isEqualTo(0.5);
        assertThat(comparison.treatmentPassRate()).isEqualTo(1.0);
        assertThat(comparison.lift()).isEqualTo(0.5);
        assertThat(comparison.flags())
            .contains(ComparisonReport.FLAG_TREATMENT_SATURATED);
    }

    @Test
    void missingArmBlocksAndWithholdsHeadline() {
        var report = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 1.0)));
        assertThat(report.blocked()).isTrue();
        assertThat(report.withheld()).isTrue();
        assertThat(report.blockedPairs()).hasSize(1);
        assertThat(report.statusCounts()).containsEntry(CaseStatus.MISSING, 1L)
            .containsEntry(CaseStatus.PASSED, 1L);

        var comparison = report.comparisons().get(0);
        assertThat(comparison.headlinePublished()).isFalse();
        assertThat(comparison.lift()).isNull();
        assertThat(comparison.controlPassRate()).isNull();
        assertThat(comparison.treatmentPassRate()).isNull();
        assertThat(comparison.blockedPairs()).isEqualTo(1);
    }

    @Test
    void duplicateArmBlocks() {
        var report = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.PASSED, 1.0)));
        assertThat(report.blocked()).isTrue();
        assertThat(report.cases().get(0).controlStatus()).isEqualTo(CaseStatus.DUPLICATE);
        assertThat(report.blockedPairs().get(0).reasons()).anySatisfy(reason ->
            assertThat(reason).contains("without_docs duplicate"));
    }

    @Test
    void skippedAndErroredArmsBlock() {
        var skipped = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.SKIPPED, null),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.PASSED, 1.0)));
        assertThat(skipped.blocked()).isTrue();
        assertThat(skipped.blockedPairs().get(0).reasons()).anySatisfy(reason ->
            assertThat(reason).contains("skipped"));

        var errored = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.ERRORED, null),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.ERRORED, null)));
        assertThat(errored.blocked()).isTrue();
        assertThat(errored.blockedPairs().get(0).reasons()).anySatisfy(reason ->
            assertThat(reason).contains("errored"));
    }

    @Test
    void passedStatusWithoutScoreIsUnscoredAndBlocks() {
        var report = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, null),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.PASSED, 1.0)));
        assertThat(report.blocked()).isTrue();
        assertThat(report.cases().get(0).controlStatus()).isEqualTo(CaseStatus.UNSCORED);
    }

    @Test
    void flagsNoLiftAndNegativeDelta() {
        var noLift = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 0.5),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.PASSED, 0.5),
            obs("case", DocumentationVariant.WITHOUT_DOCS, 2, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITH_DOCS, 2, CaseStatus.PASSED, 1.0)));
        assertThat(noLift.hasFlag(ComparisonReport.FLAG_NO_LIFT)).isTrue();

        var negative = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.PASSED, 0.5)));
        assertThat(negative.hasFlag(ComparisonReport.FLAG_NEGATIVE_DELTA)).isTrue();
        assertThat(negative.hasFlag(ComparisonReport.FLAG_CONTROL_SATURATED)).isTrue();
    }

    @Test
    void flagsFlakyOnlyForMultipleRuns() {
        var twoRuns = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITHOUT_DOCS, 2, CaseStatus.FAILED, 0.0),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITH_DOCS, 2, CaseStatus.PASSED, 1.0)));
        assertThat(twoRuns.hasFlag(ComparisonReport.FLAG_FLAKY)).isTrue();

        var singleRun = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.FAILED, 0.0)));
        assertThat(singleRun.hasFlag(ComparisonReport.FLAG_FLAKY)).isFalse();
    }

    @Test
    void expectedArmsFromPlanMarkAbsentPairsMissing() {
        var plan = TaskPlan.expand(
            List.of(new EvalCaseDescriptor("set", "case")), 1);
        var report = ComparisonReport.summarize(List.of(), plan.ids());
        assertThat(report.cases()).hasSize(1);
        assertThat(report.cases().get(0).controlStatus()).isEqualTo(CaseStatus.MISSING);
        assertThat(report.cases().get(0).treatmentStatus()).isEqualTo(CaseStatus.MISSING);
        assertThat(report.blocked()).isTrue();
        assertThat(report.statusCounts()).containsEntry(CaseStatus.MISSING, 2L);
        assertThat(report.comparisons().get(0).eligiblePairs()).isZero();
        assertThat(report.comparisons().get(0).totalPairs()).isEqualTo(1);
    }

    @Test
    void countsUsageUnavailableAndDuration() {
        var report = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 1.0),
            obs("case", DocumentationVariant.WITH_DOCS, 1, CaseStatus.PASSED, 1.0)));
        assertThat(report.usageUnavailable()).isEqualTo(2L);
        assertThat(report.totalDurationMs()).isEqualTo(20L);
    }

    @Test
    void textReportMentionsWithheldHeadline() {
        var report = ComparisonReport.summarize(List.of(
            obs("case", DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.PASSED, 1.0)));
        var text = ComparisonReportText.render(report);
        assertThat(text)
            .contains("withheld because pairs are blocked")
            .contains("Blocked pairs")
            .contains("with_docs missing");
    }

    private static EvalObservation obs(String caseName, DocumentationVariant variant,
                                       int run, CaseStatus status, Double score) {
        return new EvalObservation("set", caseName, variant, run, status, score, "",
            null, 10L, null, Map.of());
    }
}
