package com.pijava.coding.agent.core;

import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Per-prompt overrides applied before a run starts (Phase 3 §10).
 *
 * @param systemPrompt      system prompt override (null = keep harness value)
 * @param thinkingLevel     thinking level override (null = keep harness value)
 * @param streamingBehavior 运行中提交时的排队行为（A5，docs/25；null = 未指定，
 *                          运行中无此值则在调用线程被路由门拒绝）
 */
public record PromptConfig(
    String systemPrompt,
    ModelThinkingLevel thinkingLevel,
    StreamingBehavior streamingBehavior
) {
    /** Default config: no overrides. */
    public static PromptConfig defaults() {
        return new PromptConfig(null, null, null);
    }

    /** 附排队行为，其余沿用默认（A5 RPC prompt 路由用）。 */
    public static PromptConfig withStreamingBehavior(StreamingBehavior behavior) {
        return new PromptConfig(null, null, behavior);
    }
}
