package com.pijava.agent.hook;

import com.pijava.ai.message.Message;

/**
 * Context for {@code message_end} hooks（docs/15，pi {@code MessageEndEvent} 的引擎侧对位）。
 *
 * @param lane    lane name
 * @param runId   current run identifier
 * @param message the finalized message（链式替换时前钩的产物，pi {@code currentMessage}）
 * @param role    pi 视角的 message role。custom 队列项的 LLM 投影是 {@code UserMessage}，
 *                但 pi 的 {@code message_end} 载荷仍是 role＝{@code "custom"} 的
 *                AgentMessage（D322）—— 事件面用本字段对位，message 本体不动。
 */
public record MessageEndContext(String lane, String runId, Message message, String role) {
    /** 兼容构造器：role 默认取 {@code message.role()}（存量调用点零改签）。 */
    public MessageEndContext(String lane, String runId, Message message) {
        this(lane, runId, message, message.role());
    }
}
