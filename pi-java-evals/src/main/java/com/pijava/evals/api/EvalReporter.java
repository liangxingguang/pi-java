package com.pijava.evals.api;

import java.util.List;

/**
 * Receives evaluation results as they complete.
 */
public interface EvalReporter {

    /**
     * Called after each case finishes.
     *
     * @param result the case result
     */
    void onResult(EvalResult result);

    /**
     * Called after a suite finishes.
     *
     * @param suiteName suite name
     * @param results   all case results
     */
    default void onSuite(String suiteName, List<EvalResult> results) {
        // optional
    }
}
