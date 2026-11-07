package com.pijava.coding.agent.extension;

/**
 * 批 26（docs/37 T11）：pi {@code MarkdownTransformContext}（types.ts:1562-1568）。
 *
 * @param messageType    消息型（pi "user" | "assistant" | "assistant-thinking"）
 * @param isStreaming    流式渲染中为真——<b>java 恒 false</b>（渲染管线是静态投影，
 *                       裁决④窄差）
 * @param availableWidth 可用宽——<b>java 恒 -1</b>（缺席哨兵；管线宽度无感，
 *                       裁决④窄差）
 * @param messageId      持久化会话条目 id（L5 W8）；实时消息/实时渲染 ⇒ {@code null}
 * @param timestamp      持久化会话条目的 ISO 时间（L5 W8）；实时消息 ⇒ {@code null}
 */
public record MarkdownTransformContext(MarkdownMessageType messageType, boolean isStreaming,
                                       int availableWidth, String messageId,
                                       String timestamp) {

    /** 三参旧形状（实时渲染：无条目标识）。 */
    public MarkdownTransformContext(MarkdownMessageType messageType, boolean isStreaming,
                                    int availableWidth) {
        this(messageType, isStreaming, availableWidth, null, null);
    }

    /** pi {@code MarkdownTransformContext.messageType} 三值。 */
    public enum MarkdownMessageType {
        /** pi {@code "user"}（裁决⑤：本批无消费点——user 消息不走 markdown 渲染）。 */
        USER,
        /** pi {@code "assistant"}。 */
        ASSISTANT,
        /** pi {@code "assistant-thinking"}。 */
        ASSISTANT_THINKING
    }
}
