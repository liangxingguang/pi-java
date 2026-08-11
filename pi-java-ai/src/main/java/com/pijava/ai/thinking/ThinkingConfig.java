package com.pijava.ai.thinking;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Provider-specific thinking configuration parameters.
 *
 * <p>Translated from {@link ThinkingLevel} by {@link ThinkingLevelMap}
 * before each LLM request. Different providers use different parameters:
 * Anthropic uses {@code budgetTokens}, OpenAI uses {@code effort}.</p>
 *
 * @param enabled      whether extended thinking is active
 * @param budgetTokens Anthropic: thinking.budget_tokens value
 * @param effort       OpenAI: reasoning_effort value (e.g. "low", "medium", "high")
 */
public record ThinkingConfig(
    boolean enabled,
    OptionalInt budgetTokens,
    Optional<String> effort
) {
    /** Thinking disabled — the default for all providers. */
    public static final ThinkingConfig OFF =
            new ThinkingConfig(false, OptionalInt.empty(), Optional.empty());

    /** Create an Anthropic-style config with budget_tokens. */
    public static ThinkingConfig withBudget(int budgetTokens) {
        return new ThinkingConfig(true, OptionalInt.of(budgetTokens), Optional.empty());
    }

    /** Create an OpenAI-style config with reasoning_effort. */
    public static ThinkingConfig withEffort(String effort) {
        return new ThinkingConfig(true, OptionalInt.empty(), Optional.of(effort));
    }
}
