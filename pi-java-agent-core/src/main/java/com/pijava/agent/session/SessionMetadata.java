package com.pijava.agent.session;

import java.time.Instant;

/**
 * Minimal common base of session metadata (aligned with pi
 * {@code SessionMetadata}). Concrete backends extend this with their own
 * fields ({@code JsonlSessionMetadata}/{@code SqliteSessionMetadata}).
 */
public interface SessionMetadata {

    /** Unique session identifier. */
    String id();

    /** When the session was created (codec layer converts to epoch ms / ISO-8601). */
    Instant createdAt();

    /** Parent session id, or {@code null} for a root session. */
    String parentSessionId();
}