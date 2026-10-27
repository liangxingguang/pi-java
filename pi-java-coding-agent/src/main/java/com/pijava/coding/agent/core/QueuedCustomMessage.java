package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;

/**
 * 批 26（docs/37 T2）：一条待投递的扩展 custom 消息（pi {@code CustomMessage}
 * 载荷四件——customType/content/display/details；agent-session.ts:2298-2305）。
 * content 在构造点归一：null ≙ pi 的 {@code content ?? []}。
 */
record QueuedCustomMessage(String customType, CustomMessageContent content, boolean display,
                           Map<String, Object> details) {

    QueuedCustomMessage {
        if (content == null) {
            content = new CustomMessageContent.Blocks(List.of());
        }
    }

    /** 转队列项（批 26 T1 的 {@code QueuedItem.Custom}；seq 由队列管理器分配）。 */
    com.pijava.agent.harness.LaneInfo.QueuedItem.Custom toQueuedItem() {
        return new com.pijava.agent.harness.LaneInfo.QueuedItem.Custom(
            customType, content, display, details, 0);
    }
}
