package com.pijava.agent.harness;

/**
 * The next-action lookup an executor returns through, so a step executor never
 * has to know how the run loop computes its actions.
 *
 * <p>The value handed to the executors is {@link ActionExecutor#peekAction},
 * which wraps {@code computeNextAction} with the {@link LoopInvariants}
 * assertion (docs/20 §4.1, L2-⑥). Wiring a step executor to
 * {@code computeNextAction} instead would silently bypass that assert and let
 * an invariant violation through unobserved, so the seam is named rather than
 * left as a bare {@code Function<String, Action>}.</p>
 */
@FunctionalInterface
interface PeekAction {

    /** The next action for {@code laneName}, or {@code null} when there is none. */
    Action apply(String laneName);
}
