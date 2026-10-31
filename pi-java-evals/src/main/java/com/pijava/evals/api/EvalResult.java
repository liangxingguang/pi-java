package com.pijava.evals.api;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of a single evaluation case.
 *
 * <p>批 32（docs/44 §4）：由布尔扩为「分数 + 理由 + 元数据」，对齐 pi 的
 * {@code createJudge}/{@code score} 语义。{@link #passed()} 由 1.0 阈值派生，
 * {@link #detail()} 是旧布尔形态 {@code detail} 的对位（= {@link #reason()}）。</p>
 *
 * @param caseName case name
 * @param score    0..1 score ({@code 1.0} = pass)
 * @param reason   rationale / failure detail; empty when there is nothing to say
 * @param metadata judge metadata (never {@code null})
 * @param duration wall time spent running the case
 */
public record EvalResult(
    String caseName,
    double score,
    String reason,
    Map<String, Object> metadata,
    Duration duration
) {
    /** Rejects nulls and normalizes reason/metadata. */
    public EvalResult {
        Objects.requireNonNull(caseName, "caseName");
        Objects.requireNonNull(duration, "duration");
        reason = reason == null ? "" : reason;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** {@code true} when the case scored a full 1.0. */
    public boolean passed() {
        return score >= 1.0;
    }

    /** Legacy accessor for the failure detail (alias of {@link #reason()}). */
    public String detail() {
        return reason;
    }

    /**
     * Passing result.
     *
     * @param name case name
     * @param duration elapsed time
     * @return passing result
     */
    public static EvalResult passed(String name, Duration duration) {
        return new EvalResult(name, 1.0, "", Map.of(), duration);
    }

    /**
     * Failing result.
     *
     * @param name case name
     * @param detail failure detail
     * @param duration elapsed time
     * @return failing result
     */
    public static EvalResult failed(String name, String detail, Duration duration) {
        return new EvalResult(name, 0.0, detail, Map.of(), duration);
    }

    /**
     * Scored result.
     *
     * @param name case name
     * @param score 0..1 score
     * @param reason rationale
     * @param metadata judge metadata (nullable)
     * @param duration elapsed time
     * @return scored result
     */
    public static EvalResult scored(String name, double score, String reason,
                                    Map<String, Object> metadata, Duration duration) {
        return new EvalResult(name, score, reason, metadata, duration);
    }
}
