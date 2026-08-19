package com.pijava.evals.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of a single evaluation case.
 *
 * @param caseName case name
 * @param passed   {@code true} if the case succeeded
 * @param detail   failure detail, or empty on success
 * @param duration wall time spent running the case
 */
public record EvalResult(
    String caseName,
    boolean passed,
    String detail,
    Duration duration
) {
    /**
     * Rejects a null duration and normalizes a null detail to empty.
     */
    public EvalResult {
        Objects.requireNonNull(caseName, "caseName");
        Objects.requireNonNull(duration, "duration");
        detail = detail == null ? "" : detail;
    }

    /**
     * Passing result.
     *
     * @param name case name
     * @param duration elapsed time
     * @return passing result
     */
    public static EvalResult passed(String name, Duration duration) {
        return new EvalResult(name, true, "", duration);
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
        return new EvalResult(name, false, detail, duration);
    }
}
