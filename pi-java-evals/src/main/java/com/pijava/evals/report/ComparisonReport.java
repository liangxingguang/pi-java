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

import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.TaskPlan;

/**
 * 批 32（docs/44 §5.2）：docs-lift 配对报告 —— pi
 * {@code summarizeReport}/{@code report.ts} 对位。
 *
 * <p>按 {@code (evalSet, case, run)} 配对两变体。任一臂
 * {@code skipped/pending/unscored/errored/missing/duplicate} ⇒ 该对 blocked；
 * 存在 blocked 时头部通过率与 headline lift 一并 withhold，但逐对原因仍输出。</p>
 *
 * @param cases             per-pair comparisons, sorted by pair key
 * @param blocked           whether any pair is blocked
 * @param withheld          same as {@code blocked}: headline numbers withheld
 * @param control           without_docs summary
 * @param treatment         with_docs summary
 * @param lift              mean {@code treatment - control}, {@code null} when blocked
 * @param flags             flag names (see {@code FLAG_*} constants)
 * @param operationalTotals arm counts by status
 * @param totalDurationMs   summed observation durations
 * @param usageUnavailable  observations without usage (not folded to zero)
 */
public record ComparisonReport(
    List<CaseComparison> cases,
    boolean blocked,
    boolean withheld,
    ScopeSummary control,
    ScopeSummary treatment,
    Double lift,
    List<String> flags,
    Map<CaseStatus, Long> operationalTotals,
    long totalDurationMs,
    long usageUnavailable
) {

    /** 所有配对差值均为 0。 */
    public static final String FLAG_NO_LIFT = "no-lift";

    /** 出现负差值。 */
    public static final String FLAG_NEGATIVE_DELTA = "negative-delta";

    /** without_docs 全部满分。 */
    public static final String FLAG_CONTROL_SATURATED = "control-saturated";

    /** with_docs 全部满分。 */
    public static final String FLAG_TREATMENT_SATURATED = "treatment-saturated";

    /** 同一变体多 run 分数不一致。 */
    public static final String FLAG_FLAKY = "flaky";

    /** Normalizes collections. */
    public ComparisonReport {
        cases = List.copyOf(cases);
        flags = List.copyOf(flags);
        var totals = new EnumMap<CaseStatus, Long>(CaseStatus.class);
        totals.putAll(operationalTotals);
        operationalTotals = Collections.unmodifiableMap(totals);
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
     * Per-variant aggregate.
     *
     * @param arms     expected arm count for this variant
     * @param passed   passed arm count
     * @param failed   failed arm count
     * @param passRate passed / arms, {@code null} when withheld
     */
    public record ScopeSummary(long arms, long passed, long failed, Double passRate) {
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
        var passCounts = new EnumMap<DocumentationVariant, long[]>(
            DocumentationVariant.class);
        passCounts.put(DocumentationVariant.WITHOUT_DOCS, new long[2]);
        passCounts.put(DocumentationVariant.WITH_DOCS, new long[2]);
        var totals = new EnumMap<CaseStatus, Long>(CaseStatus.class);
        for (var key : keys) {
            var byVariant = grouped.getOrDefault(key,
                new EnumMap<DocumentationVariant, List<EvalObservation>>(
                    DocumentationVariant.class));
            var comparison = compare(key,
                byVariant.getOrDefault(DocumentationVariant.WITHOUT_DOCS, List.of()),
                byVariant.getOrDefault(DocumentationVariant.WITH_DOCS, List.of()));
            cases.add(comparison);
            count(comparison.controlStatus(), passCounts.get(DocumentationVariant.WITHOUT_DOCS));
            count(comparison.treatmentStatus(), passCounts.get(DocumentationVariant.WITH_DOCS));
            totals.merge(comparison.controlStatus(), 1L, Long::sum);
            totals.merge(comparison.treatmentStatus(), 1L, Long::sum);
        }

        var blocked = cases.stream().anyMatch(CaseComparison::blocked);
        var control = summary(cases.size(), passCounts.get(DocumentationVariant.WITHOUT_DOCS),
            blocked);
        var treatment = summary(cases.size(), passCounts.get(DocumentationVariant.WITH_DOCS),
            blocked);
        var deltas = cases.stream().map(CaseComparison::delta).filter(Objects::nonNull).toList();
        var lift = blocked || deltas.isEmpty()
            ? null : (Double) deltas.stream().mapToDouble(Double::doubleValue).average()
                .orElse(0.0);
        var flags = flags(cases, controls(cases), treatments(cases), deltas, observations);
        var duration = observations.stream().mapToLong(EvalObservation::durationMs).sum();
        var usageUnavailable = observations.stream().filter(o -> o.usage() == null).count();
        return new ComparisonReport(List.copyOf(cases), blocked, blocked, control, treatment,
            lift, flags, totals, duration, usageUnavailable);
    }

    /**
     * {@code true} when the flag is present.
     *
     * @param flag flag name
     * @return whether the flag is present
     */
    public boolean hasFlag(String flag) {
        return flags.contains(flag);
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
            ? treatmentScore - controlScore : null;
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

    private static ScopeSummary summary(long arms, long[] counts, boolean blocked) {
        var passRate = blocked || arms == 0 ? null : counts[0] / (double) arms;
        return new ScopeSummary(arms, counts[0], counts[1], passRate);
    }

    private static void count(CaseStatus status, long[] counts) {
        if (status == CaseStatus.PASSED) {
            counts[0]++;
        } else if (status == CaseStatus.FAILED) {
            counts[1]++;
        }
    }

    private static List<Double> controls(List<CaseComparison> cases) {
        return cases.stream().map(CaseComparison::controlScore).filter(Objects::nonNull).toList();
    }

    private static List<Double> treatments(List<CaseComparison> cases) {
        return cases.stream().map(CaseComparison::treatmentScore).filter(Objects::nonNull)
            .toList();
    }

    private static List<String> flags(List<CaseComparison> cases, List<Double> controls,
                                      List<Double> treatments, List<Double> deltas,
                                      List<EvalObservation> observations) {
        var flags = new ArrayList<String>();
        if (!deltas.isEmpty() && deltas.stream().allMatch(delta -> delta == 0.0)) {
            flags.add(FLAG_NO_LIFT);
        }
        if (deltas.stream().anyMatch(delta -> delta < 0.0)) {
            flags.add(FLAG_NEGATIVE_DELTA);
        }
        if (!controls.isEmpty() && controls.stream().allMatch(score -> score == 1.0)) {
            flags.add(FLAG_CONTROL_SATURATED);
        }
        if (!treatments.isEmpty() && treatments.stream().allMatch(score -> score == 1.0)) {
            flags.add(FLAG_TREATMENT_SATURATED);
        }
        if (isFlaky(observations)) {
            flags.add(FLAG_FLAKY);
        }
        return List.copyOf(flags);
    }

    private static boolean isFlaky(List<EvalObservation> observations) {
        var byVariantCase = new LinkedHashMap<String, Map<Integer, Double>>();
        for (var observation : observations) {
            if (observation.score() == null) {
                continue;
            }
            var key = observation.variant().wireName() + "|" + observation.evalSet()
                + "|" + observation.caseName();
            byVariantCase.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                .putIfAbsent(observation.run(), observation.score());
        }
        for (var runs : byVariantCase.values()) {
            if (runs.size() > 1 && Set.copyOf(runs.values()).size() > 1) {
                return true;
            }
        }
        return false;
    }
}
