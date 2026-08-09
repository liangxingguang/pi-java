package com.pijava.agent.session;

import java.time.Instant;

/**
 * Lightweight session descriptor returned by {@link SessionRepository}.
 *
 * @param id          unique session identifier
 * @param displayName human-readable session name
 * @param createdAt   when the session was created
 * @param updatedAt   when the session was last modified
 * @param entryCount  approximate number of entries
 */
public record Session(
    String id,
    String displayName,
    Instant createdAt,
    Instant updatedAt,
    long entryCount
) {}
