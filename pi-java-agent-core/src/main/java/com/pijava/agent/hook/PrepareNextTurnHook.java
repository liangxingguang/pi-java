package com.pijava.agent.hook;

/**
 * A {@code prepare_next_turn} hook.
 *
 * <p>Return a {@link TurnUpdate} to modify the next turn's model/thinking
 * level, or {@code null} to change nothing. Multiple hooks chain: each
 * non-null update merges over the accumulated result.</p>
 */
@FunctionalInterface
public interface PrepareNextTurnHook {

    /**
     * Prepare the configuration for the next turn.
     *
     * @param ctx turn context
     * @return the update to apply, or null for no change
     */
    TurnUpdate prepareNextTurn(PrepareNextTurnContext ctx);
}
