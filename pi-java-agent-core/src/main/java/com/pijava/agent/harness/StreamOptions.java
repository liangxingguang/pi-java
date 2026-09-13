package com.pijava.agent.harness;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.pijava.ai.thinking.ThinkingConfig;

/**
 * Extra options passed to {@link StreamFn} on each LLM call.
 *
 * <p>Aligned with pi's {@code SimpleStreamOptions}（{@code packages/ai/src/types.ts:314-322}）
 * plus the limits inherited from {@code StreamOptions}（{@code :179-199}）。
 * pi 的 options 类型里**没有 tools** —— 工具定义属于 {@link Context}，
 * 见该类注释。</p>
 *
 * @param maxTokens    max output tokens (empty = use model default)
 * @param temperature  sampling temperature (empty = use model default)
 * @param thinking     thinking configuration (translated from {@code ModelThinkingLevel})
 */
public record StreamOptions(
    OptionalInt maxTokens,
    OptionalDouble temperature,
    ThinkingConfig thinking
) {
    /** Default options: no max tokens, no temperature, no thinking. */
    public static StreamOptions defaults() {
        return new StreamOptions(
            OptionalInt.empty(),
            OptionalDouble.empty(),
            ThinkingConfig.OFF
        );
    }
}
