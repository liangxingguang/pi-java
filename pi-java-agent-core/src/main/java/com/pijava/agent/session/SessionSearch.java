package com.pijava.agent.session;

import java.util.List;

/**
 * Full-text session search contract (aligned with pi {@code SessionSearch}).
 * Implementations are backend-specific (SQLite FTS5; JSONL scanning is
 * optional and deferred).
 *
 * @param <TMetadata> the backend-specific metadata type
 */
public interface SessionSearch<TMetadata extends SessionMetadata> {

    /** Search sessions for {@code options.text()}, ordered by relevance. */
    List<SessionSearchHit<TMetadata>> search(SessionSearchOptions options);

    /** Release resources (Java lifecycle counterpart of pi's ownership model). */
    void close();
}
