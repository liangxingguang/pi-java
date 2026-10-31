package com.pijava.evals.report;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.pijava.evals.harness.HarnessUsage;
import com.pijava.evals.plan.DocumentationVariant;

/**
 * 批 32（docs/44 §5.2）：一次 arm 执行的可落盘观测 —— pi
 * {@code EvalObservation} 对位。
 *
 * <p>{@link #score()} 可为 {@code null}（未判分/跳过/出错）；报告不得把缺失的
 * usage 折成 0，{@link #usage()} 为 {@code null} 即「不可用」。</p>
 *
 * @param evalSet        eval set name
 * @param caseName       case name
 * @param variant        docs variant
 * @param run            1-based run number
 * @param status         case status
 * @param score          0..1 score, {@code null} when unscored
 * @param reason         rationale / failure detail
 * @param usage          usage summary, {@code null} when unavailable
 * @param durationMs     wall time
 * @param sessionArtifact copied session artifact path, {@code null} when absent
 * @param metrics        judge metrics (never {@code null})
 */
public record EvalObservation(
    String evalSet,
    String caseName,
    DocumentationVariant variant,
    int run,
    CaseStatus status,
    Double score,
    String reason,
    HarnessUsage usage,
    long durationMs,
    String sessionArtifact,
    Map<String, Object> metrics
) {

    /** Rejects nulls and normalizes reason/metrics. */
    public EvalObservation {
        Objects.requireNonNull(evalSet, "evalSet");
        Objects.requireNonNull(caseName, "caseName");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(status, "status");
        if (run < 1) {
            throw new IllegalArgumentException("run must be >= 1");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0");
        }
        reason = reason == null ? "" : reason;
        metrics = metrics == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }

    /**
     * Arm identifier shared with {@code TaskPlan.Arm#id()}.
     *
     * @return {@code <evalSet>/<case>#<variant>#<run>}
     */
    public String armId() {
        return evalSet + "/" + caseName + "#" + variant.wireName() + "#" + run;
    }

    /**
     * {@code true} when a score was recorded.
     *
     * @return whether {@link #score()} is non-null
     */
    public boolean hasScore() {
        return score != null;
    }

    /**
     * Copy with the session artifact path recorded.
     *
     * @param path artifact path (nullable)
     * @return updated observation
     */
    public EvalObservation withSessionArtifact(String path) {
        return new EvalObservation(evalSet, caseName, variant, run, status, score, reason,
            usage, durationMs, path, metrics);
    }
}
