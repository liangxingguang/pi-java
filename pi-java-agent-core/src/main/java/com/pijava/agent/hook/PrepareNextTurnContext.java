package com.pijava.agent.hook;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;

/**
 * Context for {@code prepare_next_turn} hooks.
 *
 * <p>Aligned with pi's {@code prepareNextTurn} callback
 * ({@code agent-loop.ts:226-245}): fired after a turn completes, before
 * {@code should_stop_after_turn} hooks decide whether the run continues.</p>
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
