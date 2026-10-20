package com.pijava.agent.hook;

import java.util.List;

import com.pijava.ai.message.Message;

/**
 * Context for {@code should_stop_after_turn} hooks.
 *
 * <p>Aligned with pi's {@code FinishTurn} callback（{@code types.ts:149-158}；
 * 调用点 {@code agent-loop.ts:286}）：一轮落定后、emit {@code turn_end} 之前触发。</p>
 *
 * <p>docs/15 修真：载荷类型改为循环定案的 ADT 形状 —— 旧实现喂的是
 * {@code lane.partial}（顶层 {@code AssistantMessage}，流中快照、可能未落定）
 * 与空工具结果列表；pi 给的是<b>定案消息</b>与<b>本轮真工具结果</b>。</p>
 *
 * @param lane             lane name
 * @param runId            current run identifier
 * @param assistantMessage the final assistant message of the turn
 * @param toolResults      tool result messages of the turn (may be empty)
 */
public record ShouldStopAfterTurnContext(
    String lane,
    String runId,
    Message.AssistantMessage assistantMessage,
    List<Message.ToolResultMessage> toolResults
) {
    /** Defensively copies {@code toolResults}. */
    public ShouldStopAfterTurnContext {
        toolResults = List.copyOf(toolResults);
    }
}
