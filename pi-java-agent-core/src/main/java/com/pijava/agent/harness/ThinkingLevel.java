package com.pijava.agent.harness;

/**
 * Controls the amount of extended thinking the model performs.
 *
 * <p>Mirrors the thinking budget / effort levels supported by
 * Claude and other reasoning models. Use the static constants
 * ({@link #OFF}, {@link #LOW}, …) for comparison.</p>
 */
public sealed interface ThinkingLevel {

    Off OFF = new Off();
    Low LOW = new Low();
    Medium MEDIUM = new Medium();
    High HIGH = new High();

    record Off() implements ThinkingLevel {}
    record Low() implements ThinkingLevel {}
    record Medium() implements ThinkingLevel {}
    record High() implements ThinkingLevel {}
}
