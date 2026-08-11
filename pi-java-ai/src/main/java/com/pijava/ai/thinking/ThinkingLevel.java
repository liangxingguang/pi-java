package com.pijava.ai.thinking;

import java.util.List;
import java.util.Set;

/**
 * Thinking depth — five-level scale.
 *
 * <p>Aligned with pi's {@code ThinkingLevel}: "minimal" | "low" | "medium" |
 * "high" | "xhigh" | "max". Used as keys in {@link ThinkingLevelMap} to
 * translate into provider-specific {@link ThinkingConfig} parameters.</p>
 *
 * <p>Note: "off" is NOT a ThinkingLevel — it is represented by
 * {@link ModelThinkingLevel.Off}.</p>
 */
public sealed interface ThinkingLevel {
    record Minimal() implements ThinkingLevel {}  // ~1024 tokens
    record Low() implements ThinkingLevel {}      // ~2048 tokens
    record Medium() implements ThinkingLevel {}   // ~8192 tokens
    record High() implements ThinkingLevel {}     // ~16384 tokens
    record XHigh() implements ThinkingLevel {}    // model maximum

    /**
     * Human-readable label for this level (e.g. "minimal", "low").
     * Used for serialization and display; avoids reflective
     * {@code getClass().getSimpleName().toLowerCase()} chains.
     */
    default String label() {
        return getClass().getSimpleName().toLowerCase();
    }

    /** Five levels in natural order (for clamp fallback). */
    static List<ThinkingLevel> ordered() {
        return List.of(new Minimal(), new Low(), new Medium(),
                       new High(), new XHigh());
    }

    /**
     * Fallback logic: try upward first (more thinking is usually safer than less),
     * then downward. Falls back to {@link Minimal} if nothing matches.
     * Aligned with pi {@code clampThinkingLevel()}.
     */
    static ThinkingLevel clamp(ThinkingLevel requested,
                                Set<ThinkingLevel> supported) {
        var ordered = ordered();
        if (supported.contains(requested)) return requested;
        int idx = ordered.indexOf(requested);
        // Try upward first
        for (int i = idx + 1; i < ordered.size(); i++) {
            if (supported.contains(ordered.get(i))) return ordered.get(i);
        }
        // Then downward
        for (int i = idx - 1; i >= 0; i--) {
            if (supported.contains(ordered.get(i))) return ordered.get(i);
        }
        return new Minimal(); // ultimate fallback
    }
}
