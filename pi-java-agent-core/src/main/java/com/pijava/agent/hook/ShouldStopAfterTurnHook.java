package com.pijava.agent.hook;

/**
 * A {@code should_stop_after_turn} hook.
 *
 * <p>Return {@code Boolean.TRUE} to end the run after the current turn;
 * return {@code null} to abstain (the next hook, or the default of
 * continuing, applies). Returning {@code FALSE} overrides later hooks with
 * an explicit "do not stop".</p>
 */
@FunctionalInterface
public interface ShouldStopAfterTurnHook {

    /**
     * Decide whether the run should stop after the current turn.
     *
     * @param ctx turn context
     * @return TRUE = stop, FALSE = do not stop, null = abstain
     */
    Boolean shouldStopAfterTurn(ShouldStopAfterTurnContext ctx);
}
