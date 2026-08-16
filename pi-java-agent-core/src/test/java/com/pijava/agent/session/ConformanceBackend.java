package com.pijava.agent.session;

import java.util.List;

/**
 * Backend fixture used by the conformance suite: the same 30 cases run
 * against Memory / JSONL / SQLite (Phase 4 §15).
 */
public interface ConformanceBackend extends AutoCloseable {

    /** Create a fresh session (random id) and return its facade. */
    Session<?> create(String cwd);

    /** Create a session with an explicit id (duplicate-create tests). */
    Session<?> create(String cwd, String id);

    /** Open an existing session by id. */
    Session<?> open(String id);

    /** List session metadata (newest first). */
    List<? extends SessionMetadata> list();

    /** Delete a session by id (idempotent). */
    void delete(String id);

    /** Fork a session. */
    Session<?> fork(String sourceId, ForkOptions options);

    @Override
    void close();
}
