package com.pijava.ai.model;

import java.util.Set;

/**
 * Capabilities that a model may support.
 *
 * <p>Each variant is a singleton record — use the static constants
 * ({@link #TEXT}, {@link #IMAGE_INPUT}, …) for identity comparison
 * and set membership. The sealed interface + record pattern follows
 * the Erasable Java convention.</p>
 */
public sealed interface ModelCapability {

    Text TEXT = new Text();
    ImageInput IMAGE_INPUT = new ImageInput();
    ImageOutput IMAGE_OUTPUT = new ImageOutput();
    ToolUse TOOL_USE = new ToolUse();
    Thinking THINKING = new Thinking();
    Streaming STREAMING = new Streaming();
    PromptCaching PROMPT_CACHING = new PromptCaching();
    ComputerUse COMPUTER_USE = new ComputerUse();

    /** Text generation (chat). */
    record Text() implements ModelCapability {}

    /** Image input (vision). */
    record ImageInput() implements ModelCapability {}

    /** Image output (generation) — e.g. FLUX, seedream, gemini-image. */
    record ImageOutput() implements ModelCapability {}

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
        return Set.of(TEXT, IMAGE_INPUT, TOOL_USE, THINKING, STREAMING, PROMPT_CACHING);
    }
}
