package com.pijava.ai.api;

import java.util.List;

/**
 * pi {@code utils/transcript.ts:210-219} —— 工具声明在「请求级 {@code tools} 字段」与
 * 「就地锚定」之间的切分。
 *
 * <p>能就地锚定的车道（Anthropic 的 {@code tool_addition}、Completions 的 Kimi 形
 * {@code {role:"system", tools}}、Responses 的 {@code additional_tools}）把**初始**工具放在
 * 顶层字段里，后续增量随系统消息出现；这只有在这段历史**没有删除、没有重定义**时才成立
 * （「只增」才可重放），所以其余情况一律退回完整的当前工具表。</p>
 *
 * @param requestTools     进顶层 {@code tools} 字段的那一份 ——
 *                         {@code anchorsAdditions} 为真时是**前导系统消息声明的那些**，
 *                         为假时是 {@link Transcripts#getCurrentTools} 的完整当前集
 * @param anchorsAdditions 后续系统消息是否把各自的 {@code toolsAdded} 作为**就地增量**发出去。
 *                         ⚠️ 为真时 {@code requestTools} **不含**那些增量 —— 它们只在车道侧出现
 *                         （{@code docs/51 §2 P3} 的警告）
 */
public record TranscriptTools(List<ToolDefinition> requestTools, boolean anchorsAdditions) {

    /** Compact constructor that defensively copies the request-side list. */
    public TranscriptTools {
        requestTools = List.copyOf(requestTools);
    }
}
