package com.pijava.agent.hook;

import com.pijava.ai.message.Message;

/**
 * Context for {@code message_end} hooks（docs/15，pi {@code MessageEndEvent} 的引擎侧对位）。
 *
 * @param lane    lane name
 * @param runId   current run identifier
 * @param message the finalized message（链式替换时前钩的产物，pi {@code currentMessage}）
 */
public record MessageEndContext(String lane, String runId, Message message) {}
