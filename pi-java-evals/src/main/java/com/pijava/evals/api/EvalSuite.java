package com.pijava.evals.api;

import java.util.List;

/**
 * A named collection of {@link EvalCase} instances.
 */
public interface EvalSuite {

    /** Human-readable suite name. */
    String name();

    /** Cases that make up this suite, in execution order. */
    List<EvalCase> cases();
}
