package com.pijava.ai.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pijava.ai.Usage;

/**
 * pi-messages SSE 载荷事件（对齐 pi {@code PiMessagesEvent}）。
 *
 * <p>变体携带不同字段 → sealed interface + record（CLAUDE.md 规范）。每个事件与
 * pi-java {@code StreamEvent} 近乎 1:1，实现成本最低。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PiMessagesEvent.Start.class, name = "start"),
    @JsonSubTypes.Type(value = PiMessagesEvent.TextStart.class, name = "text_start"),
    @JsonSubTypes.Type(value = PiMessagesEvent.TextDelta.class, name = "text_delta"),
    @JsonSubTypes.Type(value = PiMessagesEvent.TextEnd.class, name = "text_end"),
    @JsonSubTypes.Type(value = PiMessagesEvent.ThinkingStart.class, name = "thinking_start"),
    @JsonSubTypes.Type(value = PiMessagesEvent.ThinkingDelta.class, name = "thinking_delta"),
    @JsonSubTypes.Type(value = PiMessagesEvent.ThinkingEnd.class, name = "thinking_end"),
    @JsonSubTypes.Type(value = PiMessagesEvent.ToolCallStart.class, name = "toolcall_start"),
    @JsonSubTypes.Type(value = PiMessagesEvent.ToolCallDelta.class, name = "toolcall_delta"),
    @JsonSubTypes.Type(value = PiMessagesEvent.ToolCallEnd.class, name = "toolcall_end"),
    @JsonSubTypes.Type(value = PiMessagesEvent.Done.class, name = "done"),
    @JsonSubTypes.Type(value = PiMessagesEvent.Error.class, name = "error")
})
public sealed interface PiMessagesEvent {

    record Start() implements PiMessagesEvent {}

    record TextStart(int contentIndex) implements PiMessagesEvent {}

    record TextDelta(int contentIndex, String delta) implements PiMessagesEvent {}

    record TextEnd(int contentIndex, String content, String contentSignature)
        implements PiMessagesEvent {}

    record ThinkingStart(int contentIndex) implements PiMessagesEvent {}

    record ThinkingDelta(int contentIndex, String delta) implements PiMessagesEvent {}

    record ThinkingEnd(int contentIndex, String content, String contentSignature,
                       boolean redacted) implements PiMessagesEvent {}

    record ToolCallStart(int contentIndex, String id, String toolName)
        implements PiMessagesEvent {}

    record ToolCallDelta(int contentIndex, String delta) implements PiMessagesEvent {}

    /** pi: toolCall —— 独立 record，避免继承 ContentBlock 的多态 type 字段。 */
    record ToolCallEnd(int contentIndex, PiToolCall toolCall)
        implements PiMessagesEvent {}

    record Done(String reason, Usage usage, String responseId, RewriteImpact rewrite)
        implements PiMessagesEvent {}

    record Error(String reason, Usage usage, String errorMessage, String responseId,
                 RewriteImpact rewrite) implements PiMessagesEvent {}
}
