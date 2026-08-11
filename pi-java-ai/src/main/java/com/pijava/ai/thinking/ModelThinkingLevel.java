package com.pijava.ai.thinking;

/**
 * Model thinking mode: either off, or enabled at a specific
 * {@link ThinkingLevel}.
 *
 * <p>Aligned with pi's {@code ModelThinkingLevel = "off" | ThinkingLevel}.
 * Two-layer design: {@code Off} means thinking is disabled entirely;
 * {@code Enabled} carries the specific {@link ThinkingLevel}.</p>
 */
public sealed interface ModelThinkingLevel {
    record Off() implements ModelThinkingLevel {}
    record Enabled(ThinkingLevel level) implements ModelThinkingLevel {}

    static ModelThinkingLevel off() { return new Off(); }
    static ModelThinkingLevel of(ThinkingLevel level) { return new Enabled(level); }
}
