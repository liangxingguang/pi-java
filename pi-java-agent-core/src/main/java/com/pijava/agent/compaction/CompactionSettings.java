package com.pijava.agent.compaction;

/**
 * Settings that control context compaction behaviour.
 *
 * <p>When the context approaches the model's window limit the
 * harness triggers compaction to keep the most relevant content.</p>
 *
 * @param maxTokens             token budget for the compacted context
 * @param retentionRatio        fraction of entries to retain (0.0–1.0)
 * @param preserveSystemMessages keep system messages regardless
 * @param preserveRecentTools   keep recent tool results regardless
 */
public record CompactionSettings(
    int maxTokens,
    double retentionRatio,
    boolean preserveSystemMessages,
    boolean preserveRecentTools
) {
    /** Sensible defaults for interactive use. */
    public static CompactionSettings defaults() {
        return new CompactionSettings(100_000, 0.3, true, true);
    }
}
