package com.pijava.coding.agent.extension;

/**
 * 批 26（docs/37 T11）：pi {@code MarkdownTransformContext}（types.ts:1513-1517）。
 *
 * @param messageType    消息型（pi "user" | "assistant" | "assistant-thinking"）
 * @param isStreaming    流式渲染中为真——<b>java 恒 false</b>（渲染管线是静态投影，
 *                       裁决④窄差）
 * @param availableWidth 可用宽——<b>java 恒 -1</b>（缺席哨兵；管线宽度无感，
 *                       裁决④窄差）
 */
public record MarkdownTransformContext(MarkdownMessageType messageType, boolean isStreaming,
                                       int availableWidth) {

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
