package com.pijava.coding.agent.core;

/**
 * 并发 prompt 路由门（A5，{@code docs/25}）：pi {@code AgentSession.prompt}
 * 的压缩门（{@code agent-session.ts:1939-1943}）与流中门
 * （{@code agent-session.ts:1966-1979}）。
 *
 * <p>纯判定、无 IO、无引擎依赖，宿主层在启动驱动线程<em>之前</em>调用：
 * 门异常因此在调用线程同步抛出（与 pi 同），不会产生半截 run 或错误帧。</p>
 */
public final class PromptRouter {

    /** pi {@code agent-session.ts:1940-1942} 逐字。 */
    public static final String COMPACTING_MESSAGE =
        "Cannot submit a prompt while compaction is in progress. "
            + "Wait for compaction to finish and retry.";

    /** pi {@code agent-session.ts:1968-1970} 逐字。 */
    public static final String ALREADY_PROCESSING_MESSAGE =
        "Agent is already processing. Specify streamingBehavior "
            + "('steer' or 'followUp') to queue the message.";

    /** 路由定案：起新 run，或入 steer / followUp 队列。 */
    public enum Verdict {
        START, QUEUE_STEER, QUEUE_FOLLOW_UP
    }

    /**
     * @param compacting 压缩窗口（harness {@code isCompacting}）
     * @param running    运行窗口（harness {@code isRunning}）
     * @param behavior   调用方显式指定的排队行为，null = 未指定
     */
    public Verdict route(boolean compacting, boolean running, StreamingBehavior behavior) {
        if (compacting) {
            throw new IllegalStateException(COMPACTING_MESSAGE);
        }
        if (running) {
            if (behavior == null) {
                throw new IllegalStateException(ALREADY_PROCESSING_MESSAGE);
            }
            return behavior == StreamingBehavior.STEER
                ? Verdict.QUEUE_STEER
                : Verdict.QUEUE_FOLLOW_UP;
        }
        return Verdict.START;
    }
}
