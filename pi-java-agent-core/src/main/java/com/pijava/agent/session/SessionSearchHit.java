package com.pijava.agent.session;

import java.time.Instant;

/**
 * A full-text search hit (aligned with pi {@code SessionSearchHit}).
 *
 * @param metadata  the matched session
 * @param entryId   the matched entry
 * @param timestamp when the matched entry was created
 * @param snippet   may be null (SQLite backend does not generate snippets)
 * @param score     may be null (scanning backends have no score)
 */
public record SessionSearchHit<TMetadata extends SessionMetadata>(
    TMetadata metadata,
    String entryId,
    Instant timestamp,
    String snippet,
    Double score
) {}
