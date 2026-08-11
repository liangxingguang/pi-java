package com.pijava.agent.harness;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.thinking.ThinkingConfig;

/**
 * Extra options passed to {@link StreamFn} on each LLM call.
 *
 * <p>Aligned with pi's {@code SimpleStreamOptions}.</p>
 *
 * @param maxTokens    max output tokens (empty = use model default)
 * @param temperature  sampling temperature (empty = use model default)
 * @param thinking     thinking configuration (translated from {@code ModelThinkingLevel})
 * @param tools        tool definitions for function calling (Phase 2b)
 */
public record StreamOptions(
    OptionalInt maxTokens,
    OptionalDouble temperature,
    ThinkingConfig thinking,
    List<ToolDefinition> tools
) {
    public StreamOptions {
        tools = List.copyOf(tools);
    }

    /** Default options: no max tokens, no temperature, no thinking, no tools. */
    public static StreamOptions defaults() {
        return new StreamOptions(
            OptionalInt.empty(),
            OptionalDouble.empty(),
            ThinkingConfig.OFF,
            List.of()
        );
    }
}
