package com.pijava.agent.hook;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;

/**
 * Context for {@code prepare_next_turn} hooks.
 *
 * <p>Aligned with pi's {@code prepareNextTurn} callback
 * ({@code agent-loop.ts:186}): fired at the start of the next turn — after
 * the previous turn's {@code finishTurn} (which runs before {@code turn_end})
 * has let the run continue.</p>
 *
 * @param lane             lane name
 * @param runId            current run identifier
 * @param assistantMessage the final assistant message of the turn
 * @param toolResults      tool result messages of the turn (may be empty)
 */
public record PrepareNextTurnContext(
    String lane,
    String runId,
    AssistantMessage assistantMessage,
    List<Message> toolResults
) {
    /** Defensively copies {@code toolResults}. */
    public PrepareNextTurnContext {
        toolResults = List.copyOf(toolResults);
    }
}
