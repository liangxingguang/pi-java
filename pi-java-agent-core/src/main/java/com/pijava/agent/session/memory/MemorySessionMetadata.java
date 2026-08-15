package com.pijava.agent.session.memory;

import java.time.Instant;

import com.pijava.agent.session.SessionMetadata;

/** In-memory session metadata (test oracle / temporary sessions). */
public record MemorySessionMetadata(
    String id,
    Instant createdAt,
    String parentSessionId,
    String cwd
) implements SessionMetadata {
}