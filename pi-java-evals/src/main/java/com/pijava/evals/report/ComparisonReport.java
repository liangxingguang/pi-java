package com.pijava.evals.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.pijava.evals.harness.HarnessUsage;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.TaskPlan;

/**
 * L6 W1（docs/26 §2）：docs-lift 配对报告 —— pi {@code summarizeEvalObservations}
 * （{@code report.ts:360-414}）对位。
 *
 * <p>按 {@code (evalSet, case, run)} 配对两变体。任一臂
 * {@code skipped/pending/unscored/errored/missing/duplicate} ⇒ 该对 blocked，并整对
 * 退出统计。</p>
 *
 * <p>三处与旧实现不同、按 pi 对齐的语义：</p>
 * <ol>
 *   <li><b>汇总按 eval set 分行</b>（{@link #comparisons()}），headline 逐 set 发布：
 *       某 set 有 blocked 只冻结该 set，不牵连全局。</li>
 *   <li><b>lift 是通过率之差</b>，不是逐对分数差均值（{@code report.ts:394-397}）。</li>
 *   <li><b>flags 也看通过率</b>（{@code report.ts:337-344}）：{@code no-lift}＝两侧
 *       通过率相等，{@code negative-delta}＝treatment 更低，饱和＝该侧全 1.0。</li>
 * </ol>
 *
 * <p>{@link #cases()} 是本实现的诊断扩展（pi 只把逐 case 结果留在 workspace 里），
 * 不进任何计分口径。</p>
 *
 * @param cases            per-pair comparisons, sorted by pair key
 * @param blockedPairs     pairs that could not be scored
 * @param comparisons      per-eval-set summaries, sorted by set name
 * @param statusCounts     arm counts by status
 * @param variantTotals    per-variant operational totals
 * @param totalDurationMs  summed observation durations
 * @param usageUnavailable observations without usage (not folded to zero)
 */
public record ComparisonReport(
    List<CaseComparison> cases,
    List<BlockedPair> blockedPairs,
    List<EvalSetComparison> comparisons,
    Map<CaseStatus, Long> statusCounts,
    List<VariantTotals> variantTotals,
    long totalDurationMs,
    long usageUnavailable
) {

    /** 两侧通过率相等（{@code report.ts:340}）。 */
    public static final String FLAG_NO_LIFT = "no-lift";

    /** treatment 通过率低于 control（{@code report.ts:341}）。 */
    public static final String FLAG_NEGATIVE_DELTA = "negative-delta";

    /** without_docs 全部通过。 */
    public static final String FLAG_CONTROL_SATURATED = "control-saturated";

    /** with_docs 全部通过。 */
    public static final String FLAG_TREATMENT_SATURATED = "treatment-saturated";

    /** 同一变体多 run 的「是否通过」不一致（{@code report.ts:345-356}）。 */
    public static final String FLAG_FLAKY = "flaky";

    /** Normalizes collections. */
    public ComparisonReport {
        cases = List.copyOf(cases);
        blockedPairs = List.copyOf(blockedPairs);
        comparisons = List.copyOf(comparisons);
        variantTotals = List.copyOf(variantTotals);
        var totals = new EnumMap<CaseStatus, Long>(CaseStatus.class);
        totals.putAll(statusCounts);
        statusCounts = Collections.unmodifiableMap(totals);
    }

    /**
     * Pair key: one case at one run.
     *
     * @param evalSet  eval set name
     * @param caseName case name
     * @param run      1-based run number
     */
    public record PairKey(String evalSet, String caseName, int run)
        implements Comparable<PairKey> {

        /** Rejects nulls and non-positive run numbers. */
        public PairKey {
            Objects.requireNonNull(evalSet, "evalSet");
            Objects.requireNonNull(caseName, "caseName");
            if (run < 1) {
                throw new IllegalArgumentException("run must be >= 1");
            }
        }

        /**
         * Pair identifier.
         *
         * @return {@code <evalSet>/<case>#<run>}
         */
        public String id() {
            return evalSet + "/" + caseName + "#" + run;
        }

        @Override
        public int compareTo(PairKey other) {
            var bySet = evalSet.compareTo(other.evalSet);
            if (bySet != 0) {
                return bySet;
            }
            var byCase = caseName.compareTo(other.caseName);
            return byCase != 0 ? byCase : Integer.compare(run, other.run);
        }
    }

    /**
     * One paired case at one run.
     *
     * @param key             pair key
     * @param controlStatus   resolved without_docs status
     * @param treatmentStatus resolved with_docs status
     * @param controlScore    without_docs score, {@code null} when unscored
     * @param treatmentScore  with_docs score, {@code null} when unscored
     * @param delta           {@code treatment - control}, {@code null} when unscored
     * @param blocked         whether either arm blocks pairing
     * @param reason          blocker description (empty when not blocked)
     */
    public record CaseComparison(
        PairKey key,
        CaseStatus controlStatus,
        CaseStatus treatmentStatus,
        Double controlScore,
        Double treatmentScore,
        Double delta,
        boolean blocked,
        String reason
    ) {

        /** @return pair identifier */
        public String id() {
            return key.id();
        }
    }

    /**
     * A pair that could not be scored.
     *
     * @param evalSet  eval set name
     * @param caseName case name
     * @param run      1-based run number
     * @param reasons  blocker descriptions
     */
    public record BlockedPair(String evalSet, String caseName, int run, List<String> reasons) {

        /** Normalizes reasons. */
        public BlockedPair {
            reasons = List.copyOf(reasons);
        }

        /** @return pair identifier */
        public String id() {
            return evalSet + "/" + caseName + "#" + run;
        }
    }

    /** @return {@code true} when any pair is blocked (pi: {@code blockedPairs.length > 0}) */
    public boolean blocked() {
        return !blockedPairs.isEmpty();
    }

    /** @return {@code true} when any eval set withheld its headline */
    public boolean withheld() {
        return comparisons.stream().anyMatch(comparison -> !comparison.headlinePublished());
    }

    /**
     * {@code true} when the flag is present in any eval set.
     *
     * @param flag flag name
     * @return whether the flag is present
     */
    public boolean hasFlag(String flag) {
        return comparisons.stream().anyMatch(comparison -> comparison.flags().contains(flag));
    }

    /**
     * Summarize observations into a paired report.
     *
     * @param observations observed arms
     * @return paired report
     */
    public static ComparisonReport summarize(List<EvalObservation> observations) {
        return summarize(observations, List.of());
    }

    /**
     * Summarize observations into a paired report.
     *
     * @param observations   observed arms
     * @param expectedArmIds expected arm ids (from {@code TaskPlan#ids()}),
     *                       {@code null}/empty ⇒ derive pairs from observations
     * @return paired report
     */
    public static ComparisonReport summarize(List<EvalObservation> observations,
                                             Collection<String> expectedArmIds) {
        Objects.requireNonNull(observations, "observations");
        var keys = new TreeSet<PairKey>();
        if (expectedArmIds != null) {
            for (var armId : expectedArmIds) {
                var arm = TaskPlan.Arm.parse(armId);
                keys.add(new PairKey(arm.evalCase().evalSet(), arm.evalCase().caseName(),
                    arm.run()));
            }
        }
        var grouped = group(observations);
        keys.addAll(grouped.keySet());

        var cases = new ArrayList<CaseComparison>();
        var statusCounts = new EnumMap<CaseStatus, Long>(CaseStatus.class);
        var blockedPairs = new ArrayList<BlockedPair>();
        for (var key : keys) {
            var byVariant = grouped.getOrDefault(key,
                new EnumMap<DocumentationVariant, List<EvalObservation>>(
                    DocumentationVariant.class));
            var comparison = compare(key,
                byVariant.getOrDefault(DocumentationVariant.WITHOUT_DOCS, List.of()),
                byVariant.getOrDefault(DocumentationVariant.WITH_DOCS, List.of()));
            cases.add(comparison);
            statusCounts.merge(comparison.controlStatus(), 1L, Long::sum);
            statusCounts.merge(comparison.treatmentStatus(), 1L, Long::sum);
            if (comparison.blocked()) {
                blockedPairs.add(new BlockedPair(key.evalSet(), key.caseName(), key.run(),
                    List.of(comparison.reason())));
            }
        }

        var comparisons = summarizeSets(cases, grouped);
        var duration = observations.stream().mapToLong(EvalObservation::durationMs).sum();
        var unavailable = observations.stream().filter(o -> o.usage() == null).count();
        return new ComparisonReport(cases, blockedPairs, comparisons, statusCounts,
            List.of(VariantTotals.of(DocumentationVariant.WITHOUT_DOCS, observations),
                VariantTotals.of(DocumentationVariant.WITH_DOCS, observations)),
            duration, unavailable);
    }

    /** pi {@code summarizeEvalObservations} 的 per-set 主体（{@code report.ts:365-404}）。 */
    private static List<EvalSetComparison> summarizeSets(
            List<CaseComparison> cases,
            Map<PairKey, EnumMap<DocumentationVariant, List<EvalObservation>>> grouped) {
        var bySet = new LinkedHashMap<String, List<CaseComparison>>();
        for (var comparison : cases) {
            bySet.computeIfAbsent(comparison.key().evalSet(), ignored -> new ArrayList<>())
                .add(comparison);
        }
        var summaries = new ArrayList<EvalSetComparison>(bySet.size());
        for (var entry : bySet.entrySet()) {
            summaries.add(summarizeSet(entry.getKey(), entry.getValue(), grouped));
        }
        summaries.sort((left, right) -> left.evalSet().compareTo(right.evalSet()));
        return summaries;
    }

    private static EvalSetComparison summarizeSet(
            String evalSet,
            List<CaseComparison> cases,
            Map<PairKey, EnumMap<DocumentationVariant, List<EvalObservation>>> grouped) {
        var pairs = cases.stream().filter(comparison -> !comparison.blocked()).toList();
        long blockedPairs = cases.size() - pairs.size();
        var publishHeadline = blockedPairs == 0 && !pairs.isEmpty();
        var controlPassRate = publishHeadline ? passRate(pairs, true) : null;
        var treatmentPassRate = publishHeadline ? passRate(pairs, false) : null;
        var lift = controlPassRate == null || treatmentPassRate == null
            ? null : PairedMetricSummary.difference(treatmentPassRate, controlPassRate);
        return new EvalSetComparison(evalSet, cases.size(), pairs.size(), blockedPairs,
            controlPassRate, treatmentPassRate, lift,
            flags(evalSet, grouped, controlPassRate, treatmentPassRate),
            paired(pairs, grouped, observation -> usageMetric(observation, HarnessUsage::totalTokens)),
            paired(pairs, grouped, observation -> usageMetric(observation, HarnessUsage::toolCalls)),
            paired(pairs, grouped, observation -> (double) observation.durationMs()),
            paired(pairs, grouped, ComparisonReport::estimatedCost));
    }

    /** 通过率＝{@code score >= 1} 的配对数 / 配对数（{@code report.ts:381-386}）。 */
    private static Double passRate(List<CaseComparison> pairs, boolean control) {
        if (pairs.isEmpty()) {
            return null;
        }
        var passed = pairs.stream()
            .filter(pair -> {
                var score = control ? pair.controlScore() : pair.treatmentScore();
                return score != null && score >= 1.0;
            })
            .count();
        return (double) passed / pairs.size();
    }

    /**
     * 四组配对标量：任一臂该指标缺席 ⇒ 整对退出该指标（{@code report.ts:284-305}）。
     *
     * <p>配对未 blocked，故每组每变体恰有一条观测。</p>
     */
    private static PairedMetricSummary paired(
            List<CaseComparison> pairs,
            Map<PairKey, EnumMap<DocumentationVariant, List<EvalObservation>>> grouped,
            MetricSelector selector) {
        var control = new ArrayList<Double>();
        var treatment = new ArrayList<Double>();
        for (var pair : pairs) {
            var byVariant = grouped.get(pair.key());
            var controlValue = select(byVariant, DocumentationVariant.WITHOUT_DOCS, selector);
            var treatmentValue = select(byVariant, DocumentationVariant.WITH_DOCS, selector);
            if (controlValue == null || treatmentValue == null) {
                continue;
            }
            control.add(controlValue);
            treatment.add(treatmentValue);
        }
        return PairedMetricSummary.of(control, treatment);
    }

    /** usage 缺席 ⇒ 指标不可用（{@code null}），不得折成 0。 */
    private static Double usageMetric(EvalObservation observation,
                                      java.util.function.ToDoubleFunction<HarnessUsage> metric) {
        var usage = observation.usage();
        return usage == null ? null : metric.applyAsDouble(usage);
    }

    /** 成本可缺席（无定价的模型），缺席不折零。 */
    private static Double estimatedCost(EvalObservation observation) {
        var usage = observation.usage();
        return usage == null ? null : usage.estimatedCostUsd();
    }

    private static Double select(
            EnumMap<DocumentationVariant, List<EvalObservation>> byVariant,
            DocumentationVariant variant,
            MetricSelector selector) {
        if (byVariant == null) {
            return null;
        }
        var matches = byVariant.getOrDefault(variant, List.of());
        return matches.isEmpty() ? null : selector.select(matches.get(0));
    }

    /** Metric projection over one scored observation. */
    @FunctionalInterface
    private interface MetricSelector {

        /**
         * @param observation scored observation
         * @return metric value, {@code null} when unavailable
         */
        Double select(EvalObservation observation);
    }

    private static Map<PairKey, EnumMap<DocumentationVariant, List<EvalObservation>>> group(
            List<EvalObservation> observations) {
        var grouped = new HashMap<PairKey,
            EnumMap<DocumentationVariant, List<EvalObservation>>>();
        for (var observation : observations) {
            var key = new PairKey(observation.evalSet(), observation.caseName(),
                observation.run());
            var byVariant = grouped.computeIfAbsent(key,
                ignored -> new EnumMap<>(DocumentationVariant.class));
            byVariant.computeIfAbsent(observation.variant(), ignored -> new ArrayList<>())
                .add(observation);
        }
        return grouped;
    }

    private static CaseComparison compare(PairKey key, List<EvalObservation> control,
                                          List<EvalObservation> treatment) {
        var controlStatus = statusOf(control);
        var treatmentStatus = statusOf(treatment);
        var controlScore = scoreOf(control, controlStatus);
        var treatmentScore = scoreOf(treatment, treatmentStatus);
        var blocked = controlStatus.blocking() || treatmentStatus.blocking();
        var reasons = new ArrayList<String>();
        if (controlStatus.blocking()) {
            reasons.add(DocumentationVariant.WITHOUT_DOCS.wireName() + " "
                + controlStatus.wireName());
        }
        if (treatmentStatus.blocking()) {
            reasons.add(DocumentationVariant.WITH_DOCS.wireName() + " "
                + treatmentStatus.wireName());
        }
        var delta = !blocked && controlScore != null && treatmentScore != null
            ? PairedMetricSummary.difference(treatmentScore, controlScore) : null;
        return new CaseComparison(key, controlStatus, treatmentStatus, controlScore,
            treatmentScore, delta, blocked, String.join(", ", reasons));
    }

    private static CaseStatus statusOf(List<EvalObservation> matches) {
        if (matches.isEmpty()) {
            return CaseStatus.MISSING;
        }
        if (matches.size() > 1) {
            return CaseStatus.DUPLICATE;
        }
        var observation = matches.get(0);
        var status = observation.status();
        if ((status == CaseStatus.PASSED || status == CaseStatus.FAILED)
                && observation.score() == null) {
            return CaseStatus.UNSCORED;
        }
        return status;
    }

    private static Double scoreOf(List<EvalObservation> matches, CaseStatus status) {
        return status == CaseStatus.PASSED || status == CaseStatus.FAILED
            ? matches.get(0).score() : null;
    }

    /**
     * pi {@code comparisonFlags}（{@code report.ts:337-358}）：四臂看通过率，
     * flaky 看「是否通过」在多 run 间是否一致。
     */
    private static List<String> flags(
            String evalSet,
            Map<PairKey, EnumMap<DocumentationVariant, List<EvalObservation>>> grouped,
            Double controlPassRate, Double treatmentPassRate) {
        var flags = new ArrayList<String>();
        if (controlPassRate != null && treatmentPassRate != null) {
            if (controlPassRate.doubleValue() == treatmentPassRate.doubleValue()) {
                flags.add(FLAG_NO_LIFT);
            }
            if (treatmentPassRate < controlPassRate) {
                flags.add(FLAG_NEGATIVE_DELTA);
            }
            if (controlPassRate == 1.0) {
                flags.add(FLAG_CONTROL_SATURATED);
            }
            if (treatmentPassRate == 1.0) {
                flags.add(FLAG_TREATMENT_SATURATED);
            }
        }
        if (isFlaky(evalSet, grouped)) {
            flags.add(FLAG_FLAKY);
        }
        return List.copyOf(flags);
    }

    private static boolean isFlaky(
            String evalSet,
            Map<PairKey, EnumMap<DocumentationVariant, List<EvalObservation>>> grouped) {
        var byVariantCase = new LinkedHashMap<String, Map<Integer, String>>();
        for (var entry : grouped.entrySet()) {
            if (!entry.getKey().evalSet().equals(evalSet)) {
                continue;
            }
            for (var variantEntry : entry.getValue().entrySet()) {
                for (var observation : variantEntry.getValue()) {
                    if (observation.score() == null) {
                        continue;
                    }
                    var key = variantEntry.getKey().wireName() + "|" + observation.evalSet()
                        + "|" + observation.caseName();
                    byVariantCase.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(observation.run(),
                            String.valueOf(observation.score() >= 1.0));
                }
            }
        }
        for (var runs : byVariantCase.values()) {
            if (runs.size() > 1 && Set.copyOf(runs.values()).size() > 1) {
                return true;
            }
        }
        return false;
    }
}
