package com.pijava.evals.report;

import java.util.List;
import java.util.Map;

import com.pijava.evals.harness.HarnessUsage;
import com.pijava.evals.plan.DocumentationVariant;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L6 W1/W2（docs/26 §2.4/§3.4）：lift 语义、per-set headline 与配对标量。
 *
 * <p>本类的核心用例 {@link #liftIsPassRateDifferenceNotMeanScoreDelta()} 在旧实现
 * （逐对分数差均值）下必然失败——它钉的是 pi {@code report.ts:394-397} 的定义。</p>
 */
class ComparisonReportMetricsTest {

    @Test
    void liftIsPassRateDifferenceNotMeanScoreDelta() {
        // control 两对全过（1.0 / 1.0），treatment 一对满分一对 0.5：
        //   逐对分数差均值 = (0.0 + (-0.5)) / 2 = -0.25   ← 旧实现
        //   通过率之差     = 1/2 - 2/2           = -0.5    ← pi report.ts:394-397
        var report = ComparisonReport.summarize(List.of(
            scored("a", DocumentationVariant.WITHOUT_DOCS, 1, 1.0),
            scored("a", DocumentationVariant.WITH_DOCS, 1, 1.0),
            scored("b", DocumentationVariant.WITHOUT_DOCS, 1, 1.0),
            scored("b", DocumentationVariant.WITH_DOCS, 1, 0.5)));

        var comparison = report.comparisons().get(0);
        assertThat(comparison.controlPassRate()).isEqualTo(1.0);
        assertThat(comparison.treatmentPassRate()).isEqualTo(0.5);
        assertThat(comparison.lift()).isEqualTo(-0.5);
        assertThat(comparison.flags())
            .contains(ComparisonReport.FLAG_NEGATIVE_DELTA)
            .doesNotContain(ComparisonReport.FLAG_NO_LIFT);
    }

    @Test
    void partialScoreBelowOneCountsAsNotPassed() {
        var report = ComparisonReport.summarize(List.of(
            scored("a", DocumentationVariant.WITHOUT_DOCS, 1, 1.0),
            scored("a", DocumentationVariant.WITH_DOCS, 1, 0.99)));

        var comparison = report.comparisons().get(0);
        assertThat(comparison.treatmentPassRate()).isZero();
        assertThat(comparison.lift()).isEqualTo(-1.0);
    }

    @Test
    void headlineIsPerEvalSet() {
        var report = ComparisonReport.summarize(List.of(
            // set "ok" 两臂齐全
            new EvalObservation("ok", "c", DocumentationVariant.WITHOUT_DOCS, 1,
                CaseStatus.PASSED, 0.0, "", null, 1L, null, Map.of()),
            new EvalObservation("ok", "c", DocumentationVariant.WITH_DOCS, 1,
                CaseStatus.PASSED, 1.0, "", null, 1L, null, Map.of()),
            // set "bad" 缺 with_docs
            new EvalObservation("bad", "c", DocumentationVariant.WITHOUT_DOCS, 1,
                CaseStatus.PASSED, 1.0, "", null, 1L, null, Map.of())));

        var ok = report.comparisons().stream()
            .filter(comparison -> comparison.evalSet().equals("ok")).findFirst().orElseThrow();
        var bad = report.comparisons().stream()
            .filter(comparison -> comparison.evalSet().equals("bad")).findFirst().orElseThrow();

        assertThat(ok.headlinePublished()).isTrue();
        assertThat(ok.lift()).isEqualTo(1.0);
        assertThat(bad.headlinePublished()).isFalse();
        assertThat(bad.lift()).isNull();
        assertThat(bad.controlPassRate()).isNull();
        assertThat(bad.blockedPairs()).isEqualTo(1);
        // 全局 blocked 仍为真，但不影响 ok 的 headline
        assertThat(report.blocked()).isTrue();
    }

    @Test
    void pairedMetricsRequireBothSidesAvailable() {
        var report = ComparisonReport.summarize(List.of(
            scoredWithUsage("a", DocumentationVariant.WITHOUT_DOCS, 1, 1.0, 100.0),
            scoredWithUsage("a", DocumentationVariant.WITH_DOCS, 1, 1.0, 300.0),
            // 第二对 with_docs 没有 usage ⇒ 整对退出 token 指标
            scoredWithUsage("b", DocumentationVariant.WITHOUT_DOCS, 1, 1.0, 100.0),
            scored("b", DocumentationVariant.WITH_DOCS, 1, 1.0)));

        var tokens = report.comparisons().get(0).totalTokens();
        assertThat(tokens.eligiblePairs()).isEqualTo(1);
        assertThat(tokens.controlMean()).isEqualTo(100.0);
        assertThat(tokens.treatmentMean()).isEqualTo(300.0);
        assertThat(tokens.meanDelta()).isEqualTo(200.0);
    }

    @Test
    void variantTotalsReportCoverageNotZeroFilled() {
        var report = ComparisonReport.summarize(List.of(
            scoredWithUsage("a", DocumentationVariant.WITH_DOCS, 1, 1.0, 100.0),
            scored("b", DocumentationVariant.WITH_DOCS, 1, 1.0)));

        var withDocs = report.variantTotals().stream()
            .filter(totals -> totals.variant() == DocumentationVariant.WITH_DOCS)
            .findFirst().orElseThrow();
        assertThat(withDocs.runs()).isEqualTo(2);
        assertThat(withDocs.totalTokens().availableRuns()).isEqualTo(1);
        assertThat(withDocs.totalTokens().total()).isEqualTo(100.0);
        // 延迟不依赖 usage：两 run 都计入
        assertThat(withDocs.totalMs().availableRuns()).isEqualTo(2);
        // 成本从没出现过 ⇒ 不折成 0
        assertThat(withDocs.estimatedCostUsd().total()).isNull();
    }

    @Test
    void flakyComparesPassBooleanNotRawScore() {
        // 同一变体两 run 都「通过」（0.8 与 1.0 都 >= 1? 否——0.8 < 1）。
        // 用 1.0 与 1.0 之外再给 0.5 制造「一次过、一次不过」。
        var report = ComparisonReport.summarize(List.of(
            scored("a", DocumentationVariant.WITHOUT_DOCS, 1, 1.0),
            scored("a", DocumentationVariant.WITHOUT_DOCS, 2, 0.5),
            scored("a", DocumentationVariant.WITH_DOCS, 1, 1.0),
            scored("a", DocumentationVariant.WITH_DOCS, 2, 1.0)));
        assertThat(report.hasFlag(ComparisonReport.FLAG_FLAKY)).isTrue();

        // 两 run 分数不同但都通过 ⇒ 不算 flaky（pi report.ts:349 比较的是 score>=1）
        var stable = ComparisonReport.summarize(List.of(
            scored("a", DocumentationVariant.WITHOUT_DOCS, 1, 1.0),
            scored("a", DocumentationVariant.WITHOUT_DOCS, 2, 1.0),
            scored("a", DocumentationVariant.WITH_DOCS, 1, 1.0),
            scored("a", DocumentationVariant.WITH_DOCS, 2, 1.0)));
        assertThat(stable.hasFlag(ComparisonReport.FLAG_FLAKY)).isFalse();
    }

    @Test
    void textReportRendersCoverageAndSectionOrder() {
        var report = ComparisonReport.summarize(List.of(
            scoredWithUsage("a", DocumentationVariant.WITHOUT_DOCS, 1, 1.0, 100.0),
            scoredWithUsage("a", DocumentationVariant.WITH_DOCS, 1, 1.0, 300.0)));
        var text = ComparisonReportText.render(report);

        assertThat(text)
            .contains("Pairs  1/1 eligible")
            .contains("Pass rate  +0.0 pp (with 100.0%, without 100.0%)")
            .contains("Tokens  +200.0 (with 300.0, without 100.0, 1 pairs)")
            .contains("Operational totals");
        assertThat(text.indexOf("Pairs")).isLessThan(text.indexOf("Pass rate"));
        assertThat(text.indexOf("Pass rate")).isLessThan(text.indexOf("Tokens"));
        assertThat(text.indexOf("Est. cost")).isLessThan(text.indexOf("Operational totals"));
    }

    private static EvalObservation scored(String caseName, DocumentationVariant variant,
                                          int run, double score) {
        return new EvalObservation("set", caseName, variant, run, CaseStatus.PASSED, score,
            "", null, 10L, null, Map.of());
    }

    private static EvalObservation scoredWithUsage(String caseName,
                                                   DocumentationVariant variant, int run,
                                                   double score, double tokens) {
        var usage = new HarnessUsage("openai", "gpt", tokens, 0, 0, 0, tokens, 0, null);
        return new EvalObservation("set", caseName, variant, run, CaseStatus.PASSED, score,
            "", usage, 10L, null, Map.of());
    }
}
