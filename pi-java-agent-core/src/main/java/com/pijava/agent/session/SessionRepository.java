package com.pijava.agent.session;

import java.util.List;
import java.util.Optional;

/**
 * Session lifecycle management.
 *
 * <p>Manages the collection of all sessions: create, list, open,
 * delete, and fork. Each session is backed by a {@link SessionStorage}.</p>
 */
public interface SessionRepository {

    /** List all known sessions. */
    List<Session> listSessions();

    /** Create a new session. */
    Session createSession(String displayName);

    /** Open an existing session for reading and writing. */
    Optional<SessionStorage<?>> openSession(String sessionId);

    /** Delete a session and all its data. */
    void deleteSession(String sessionId);

    /** Fork a session (create a copy with a new ID). */
    Session forkSession(String sessionId, String newDisplayName);
}
