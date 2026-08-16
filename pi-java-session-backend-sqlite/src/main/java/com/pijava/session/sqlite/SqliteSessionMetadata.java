package com.pijava.session.sqlite;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import com.pijava.agent.session.SessionMetadata;

/**
 * SQLite session metadata (aligned with pi {@code SqliteSessionMetadata}).
 * {@code name} is projected from the latest {@code name} fact.
 *
 * @param id              session id
 * @param createdAt       creation time
 * @param parentSessionId may be null
 * @param cwd             session working directory
 * @param path            database file path
 * @param name            latest session name, may be null
 * @param metadata        application metadata, may be null
 */
public record SqliteSessionMetadata(
    String id,
    Instant createdAt,
    String parentSessionId,
    String cwd,
    Path path,
    String name,
    Map<String, Object> metadata
) implements SessionMetadata {
}
