package com.pijava.ai.model;

import java.util.Set;

/**
 * Capabilities that a model may support.
 *
 * <p>Each capability corresponds to a feature that callers can
 * query before sending a request.</p>
 */
public enum ModelCapability {
    /** Text generation (chat). */
    TEXT,
    /** Image input (vision). */
    IMAGE_INPUT,
    /** Tool / function calling. */
    TOOL_USE,
    /** Extended thinking / reasoning. */
    THINKING,
    /** Streaming responses. */
    STREAMING,
    /** Prompt caching (e.g. Anthropic prompt cache). */
    PROMPT_CACHING,
    /** Computer use (screenshot + mouse/keyboard). */
    COMPUTER_USE;

    /**
     * Returns the set of capabilities typically expected of a
     * frontier chat model.
     */
    public static Set<ModelCapability> frontierDefaults() {
        return Set.of(TEXT, IMAGE_INPUT, TOOL_USE, THINKING, STREAMING, PROMPT_CACHING);
    }
}
