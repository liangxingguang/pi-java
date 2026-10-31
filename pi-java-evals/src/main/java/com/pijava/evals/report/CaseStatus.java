package com.pijava.evals.report;

/**
 * 批 32（docs/44 §5.2）：单个 arm 的判定状态 —— pi
 * {@code report.ts} 的 {@code classifyCaseStatus} 对位。
 */
public enum CaseStatus {

    /** Scored 1.0 (or at/above the threshold). */
    PASSED("passed"),

    /** Scored below the threshold or explicitly failed. */
    FAILED("failed"),

    /** The case declined to run (assumption skipped). */
    SKIPPED("skipped"),

    /** The case was not executed yet. */
    PENDING("pending"),

    /** The case ran but produced no score. */
    UNSCORED("unscored"),

    /** The arm crashed or its artifact could not be read. */
    ERRORED("errored"),

    /** No observation was recorded for the expected arm. */
    MISSING("missing"),

    /** More than one observation was recorded for the expected arm. */
    DUPLICATE("duplicate");

    private final String wireName;

    CaseStatus(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Wire literal used in observations and reports.
     *
     * @return lower-case status name
     */
    public String wireName() {
        return wireName;
    }

    /**
     * {@code true} unless the arm produced a pass/fail score.
     *
     * @return whether this status blocks pairing and the headline report
     */
    public boolean blocking() {
        return this != PASSED && this != FAILED;
    }

    /**
     * Parse a wire literal.
     *
     * @param value wire literal
     * @return the matching status
     * @throws IllegalArgumentException if the literal is unknown
     */
    public static CaseStatus fromWire(String value) {
        for (var status : values()) {
            if (status.wireName.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown case status: " + value);
    }
}
