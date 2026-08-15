package com.pijava.agent.compaction;

/**
 * Compaction configuration (aligned with pi {@code CompactionSettings}).
 *
 * @param enabled         master switch; {@code false} disables compaction
 * @param reserveTokens   context-window reserve: compaction triggers when
 *                        {@code contextTokens > window - reserveTokens}
 * @param keepRecentTokens recent-token budget that determines the cut point
 */
public record CompactionSettings(
    boolean enabled,
    int reserveTokens,
    int keepRecentTokens
) {

    /** Defaults: enabled, 16384 reserved, 20000 recent tokens kept. */
    public static CompactionSettings defaults() {
        return new CompactionSettings(true, 16384, 20000);
    }
}