package com.pijava.agent.hook;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;

/**
 * Context for {@code should_stop_after_turn} hooks.
 *
 * <p>Aligned with pi's {@code FinishTurn} callback（{@code types.ts:149-158}；
 * 调用点 {@code agent-loop.ts:286}）：一轮落定后、emit {@code turn_end} 之前触发。</p>
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
