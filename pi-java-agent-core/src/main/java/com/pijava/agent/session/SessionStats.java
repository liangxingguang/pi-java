package com.pijava.agent.session;

/**
 * Session statistics (aligned with pi {@code SessionStats}). Usage records are
 * accumulated incrementally by the storage.
 *
 * @param messageCount  number of message entries
 * @param cachedTokens  accumulated {@code cacheRead} tokens
 * @param uncachedTokens accumulated {@code input + cacheWrite} tokens
 * @param totalTokens   accumulated {@code totalTokens}
 * @param costTotal     accumulated {@code cost.total}
 */
public record SessionStats(
    long messageCount,
    double cachedTokens,
    double uncachedTokens,
    double totalTokens,
    double costTotal
) {

    /** All-zero statistics. */
    public static SessionStats zero() {
        return new SessionStats(0, 0, 0, 0, 0);
    }
}
