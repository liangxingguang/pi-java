package com.pijava.coding.agent.core;

import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Per-prompt overrides applied before a run starts (Phase 3 §10).
 *
 * @param systemPrompt   system prompt override (null = keep harness value)
 * @param thinkingLevel  thinking level override (null = keep harness value)
 */
public record PromptConfig(
    String systemPrompt,
    ModelThinkingLevel thinkingLevel
) {
    /** Default config: no overrides. */
    public static PromptConfig defaults() {
        return new PromptConfig(null, null);
    }
}
