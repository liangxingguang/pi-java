package com.pijava.agent.hook;

/**
 * A {@code message_end} hook（docs/15 裁决① —— pi {@code emitMessageEnd} 的引擎侧落点，
 * {@code runner.ts:1144-1181}）。
 *
 * <p>返回非 null 即<b>替换</b>本轮定案消息：{@code PiLaneSink} 在消息进工作副本/转录/
 * 下游事件之前换引用，三处同换（pi 的 {@code _replaceMessageInPlace} 对位——java 的
 * {@code Message} 不可变、宿主事件在落盘之后，唯一能保语义的缝在 sink）。返回
 * {@code null} 弃权。</p>
 */
@FunctionalInterface
public interface MessageEndHook {

    /**
     * Invoked when a message is finalized, before it enters lane state.
     *
     * @param ctx message-end context（{@code message} 是链中值：前钩的替换结果）
     * @return replacement message, or {@code null} to abstain
     */
    com.pijava.ai.message.Message messageEnd(MessageEndContext ctx);
}
