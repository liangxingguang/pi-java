package com.pijava.ai.model;

import java.util.Set;

/**
 * Capabilities that a model may support.
 *
 * <p>Each capability corresponds to a feature that callers can
 * query before sending a request. The sealed interface + record
 * pattern follows the Erasable Java convention.</p>
 */
public sealed interface ModelCapability {

    /** Text generation (chat). */
    record Text() implements ModelCapability {}

    /** Image input (vision). */
    record ImageInput() implements ModelCapability {}

    /** Tool / function calling. */
    record ToolUse() implements ModelCapability {}

    /** Extended thinking / reasoning. */
    record Thinking() implements ModelCapability {}

    /** Streaming responses. */
    record Streaming() implements ModelCapability {}

    /** Prompt caching (e.g. Anthropic prompt cache). */
    record PromptCaching() implements ModelCapability {}

    /** Computer use (screenshot + mouse/keyboard). */
    record ComputerUse() implements ModelCapability {}

    /**
     * Returns the set of capabilities typically expected of a
     * frontier chat model.
     */
    static Set<ModelCapability> frontierDefaults() {
        return Set.of(
            new Text(), new ImageInput(), new ToolUse(),
            new Thinking(), new Streaming(), new PromptCaching()
        );
    }
}
