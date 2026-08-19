package com.pijava.evals.api;

/**
 * A single evaluation case that can run against an {@link EvalContext}.
 */
public interface EvalCase {

    /** Human-readable case name, e.g. {@code C1-stream-start-end}. */
    String name();

    /**
     * Execute this case.
     *
     * @param ctx shared evaluation context
     * @return pass/fail result
     */
    EvalResult run(EvalContext ctx);
}
