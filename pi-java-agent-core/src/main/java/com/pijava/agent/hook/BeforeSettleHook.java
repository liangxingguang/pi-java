package com.pijava.agent.hook;

/**
 * A {@code before_settle} hook（docs/15 裁决② —— pi {@code _runBeforeSettleBoundary} 的
 * 引擎侧落点，{@code agent-session.ts:1839,1893-1919}）。
 *
 * <p>firing 点在驱动循环的 settle 前最后一道门：post-run 检查（重试/压缩/队列）全说
 * 「收」之后、{@code finishRun} 之前。返回 {@code TRUE} ⇒ 再 continue 一个 pass；
 * {@code FALSE} ⇒ 收口；{@code null} 弃权（调用方按无桥行为收口——pi 此刻队列必空，
 * 弃权与 {@code hasQueuedMessages()} 同值）。</p>
 */
@FunctionalInterface
public interface BeforeSettleHook {

    /**
     * Invoked once per run, at the last gate before settlement.
     *
     * @param ctx settle context
     * @return TRUE = 再跑一轮；FALSE = 收口；null = 弃权
     */
    Boolean beforeSettle(BeforeSettleContext ctx);
}
