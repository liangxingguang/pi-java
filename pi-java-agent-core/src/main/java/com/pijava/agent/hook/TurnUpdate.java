package com.pijava.agent.hook;

import com.pijava.ai.model.ModelId;

/**
 * A pending per-turn configuration change produced by
 * {@code prepare_next_turn} hooks. A null field means "unchanged".
 *
 * <p>Scoped to the current run: applied atomically at the start of the next
 * turn and cleared when the run ends (pi keeps these in run-loop locals, so
 * they never leak across runs).</p>
 *
 * @param model         model for the next turn, or null to keep the current one
 * @param thinkingLevel thinking level label ("off"|"minimal"|"low"|"medium"|"high"|"xhigh"),
 *                      or null to keep the current one
 */
public record TurnUpdate(ModelId<?> model, String thinkingLevel) {}
