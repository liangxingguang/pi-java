package com.pijava.ai.api;

import java.util.List;

/**
 * pi {@code utils/transcript.ts:232-240} —— 工具声明在「请求级 {@code tools} 字段」与
 * 「就地锚定」之间的切分。
 *
 * @param requestTools          进顶层 {@code tools} 字段的那一份
 * @param foldedSystemMessages  前多少条系统消息的工具变更已经装进 {@code requestTools}；
 *                              序号从 0 开始，之后的系统消息各自就地声明
 */
public record TranscriptTools(List<ToolDefinition> requestTools, int foldedSystemMessages) {

    /** Compact constructor that defensively copies the request-side list. */
    public TranscriptTools {
        requestTools = List.copyOf(requestTools);
    }

    /** 第 {@code systemIndex} 条系统消息（从 0 数）是否需要就地声明工具变更。 */
    public boolean anchors(int systemIndex) {
        return systemIndex >= foldedSystemMessages;
    }
}
