package com.pijava.evals.harness;

import java.util.Map;

/**
 * 批 32（docs/44 §3.3）：转录事件 —— pi {@code TranscriptEvent} 的 Java 形。
 *
 * <p>三种 type：{@code message}（user/assistant 文本）、{@code tool_call}、
 * {@code tool_result}。字段按 type 取值，未用字段为 {@code null}。</p>
 *
 * @param type      {@code message} | {@code tool_call} | {@code tool_result}
 * @param role      message role (user/assistant)
 * @param content   message or result text
 * @param id        tool call id
 * @param name      tool name
 * @param arguments tool call arguments
 * @param toolCallId tool result's owning call id
 * @param toolName  tool result's tool name
 * @param error     tool result is an error
 */
public record TranscriptEvent(
    String type,
    String role,
    String content,
    String id,
    String name,
    Map<String, Object> arguments,
    String toolCallId,
    String toolName,
    boolean error
) {

    /**
     * Message event.
     *
     * @param role user/assistant
     * @param content text
     * @return event
     */
    public static TranscriptEvent message(String role, String content) {
        return new TranscriptEvent("message", role, content, null, null, null,
            null, null, false);
    }

    /**
     * Tool-call event.
     *
     * @param id        call id
     * @param name      tool name
     * @param arguments arguments
     * @return event
     */
    public static TranscriptEvent toolCall(String id, String name,
                                           Map<String, Object> arguments) {
        return new TranscriptEvent("tool_call", null, null, id, name, arguments,
            null, null, false);
    }

    /**
     * Tool-result event.
     *
     * @param toolCallId owning call id
     * @param toolName   tool name
     * @param content    result text
     * @param error      error flag
     * @return event
     */
    public static TranscriptEvent toolResult(String toolCallId, String toolName,
                                             String content, boolean error) {
        return new TranscriptEvent("tool_result", null, content, null, null, null,
            toolCallId, toolName, error);
    }
}
