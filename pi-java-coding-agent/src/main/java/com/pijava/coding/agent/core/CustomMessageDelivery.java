package com.pijava.coding.agent.core;

/**
 * 批 26（docs/37 T2）：pi {@code sendMessage} 的 {@code deliverAs} 投递语义
 * （types.ts:1703-1707）——STEER＝当前轮后立刻插队、FOLLOW_UP＝整轮收尾后补一轮、
 * NEXT_TURN＝随下一条用户 prompt 注入上下文。{@code null} ≙ pi 的未给。
 */
public enum CustomMessageDelivery {
    /** pi {@code "steer"}。 */
    STEER,
    /** pi {@code "followUp"}。 */
    FOLLOW_UP,
    /** pi {@code "nextTurn"}。 */
    NEXT_TURN
}
