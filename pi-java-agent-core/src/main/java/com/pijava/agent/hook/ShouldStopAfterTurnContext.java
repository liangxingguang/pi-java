package com.pijava.agent.hook;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;

/**
 * Context for {@code should_stop_after_turn} hooks.
 *
 * <p>Aligned with pi's {@code shouldStopAfterTurn} callback
 * ({@code agent-loop.ts:247-257}): fired after a completed turn, before the
 * run would continue to the next turn or drain follow-up queues.</p>
 *
 * @param lane             lane name
 * @param runId            current run identifier
 * @param assistantMessage the final assistant message of the turn
 * @param toolResults      tool result messages of the turn (may be empty)
 */
public record ShouldStopAfterTurnContext(
    String lane,
    String runId,
    AssistantMessage assistantMessage,
    List<Message> toolResults
) {
    /** Defensively copies {@code toolResults}. */
    public ShouldStopAfterTurnContext {
        toolResults = List.copyOf(toolResults);
    }
}
