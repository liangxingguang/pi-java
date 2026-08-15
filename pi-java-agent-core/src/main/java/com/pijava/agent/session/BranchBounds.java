package com.pijava.agent.session;

/**
 * Bounds of a branch scan (aligned with pi {@code BranchBounds}).
 * {@code start} defaults to the view's lane leaf; scans run from
 * {@code start} toward the root, stopping after the first match
 * (inclusive) of {@code stopAtType} or {@code stopAtId}.
 *
 * @param start       entry id where the scan starts, may be {@code null}
 * @param stopAtType  stop after the first entry of this type, may be {@code null}
 * @param stopAtId    stop after this exact entry id, may be {@code null}
 */
public record BranchBounds(String start, String stopAtType, String stopAtId) {

    /** No bounds: the whole path from start to root. */
    public static BranchBounds none() {
        return new BranchBounds(null, null, null);
    }

    /** Bound only by a fixed start entry. */
    public static BranchBounds from(String start) {
        return new BranchBounds(start, null, null);
    }
}