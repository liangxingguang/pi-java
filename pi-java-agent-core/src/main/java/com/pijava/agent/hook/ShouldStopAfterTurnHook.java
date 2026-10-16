package com.pijava.agent.hook;

import com.pijava.agent.harness.PiLoop;

/**
 * A {@code should_stop_after_turn} hook（pi 的 {@code FinishTurn}，{@code types.ts:149-158}）。
 *
 * <p>返回 {@link PiLoop.TurnDecision#END} 在本轮之后收口整个 run；返回
 * {@link PiLoop.TurnDecision#CONTINUE} 强制再来一次 provider 请求（被工具结果/
 * steering/follow-up 满足则不额外发）；返回 {@code null} 弃权（≙ pi 的
 * {@code undefined}，照常调度）。B159 起不再有「不许停」的第三态 —— pi 无此物。</p>
 */
@FunctionalInterface
public interface ShouldStopAfterTurnHook {

    /**
     * Decide what happens after the current turn.
     *
     * @param ctx turn context
     * @return END = 收口，CONTINUE = 强制再来一次请求，null = 弃权
     */
    PiLoop.TurnDecision shouldStopAfterTurn(ShouldStopAfterTurnContext ctx);
}
