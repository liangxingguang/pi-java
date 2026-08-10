package com.pijava.agent.harness;

/**
 * Controls the amount of extended thinking the model performs.
 *
 * <p>Mirrors the thinking budget / effort levels supported by
 * Claude and other reasoning models.</p>
 */
public sealed interface ThinkingLevel {
    record Off() implements ThinkingLevel {}
    record Low() implements ThinkingLevel {}
    record Medium() implements ThinkingLevel {}
    record High() implements ThinkingLevel {}
}
