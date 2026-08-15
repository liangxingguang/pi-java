package com.pijava.agent.session;

import java.util.List;

/**
 * Session lifecycle management (aligned with pi {@code SessionRepo}).
 *
 * @param <TMetadata>       metadata type
 * @param <TCreateOptions>  create options type
 * @param <TListOptions>    list options type
 */
public interface SessionRepository<
    TMetadata extends SessionMetadata,
    TCreateOptions,
    TListOptions> {

    /** Create a session and return it with a backend writer claim. */
    Session<TMetadata> create(TCreateOptions options);

    /**
     * Open a session for writing and acquire the backend writer claim
     * (SQLite: writer lease; JSONL: lock-free).
     */
    Session<TMetadata> open(TMetadata metadata);

    /** List metadata without opening sessions or acquiring writer claims. */
    List<TMetadata> list(TListOptions options);

    /** Delete a session (idempotent). */
    void delete(TMetadata metadata);

    /** Fork a source session with the given scope. */
    Session<TMetadata> fork(TMetadata source, ForkOptions options, TCreateOptions createOptions);
}