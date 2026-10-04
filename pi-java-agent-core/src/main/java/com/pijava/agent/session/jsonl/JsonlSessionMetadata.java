package com.pijava.agent.session.jsonl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import com.pijava.agent.session.SessionMetadata;

/**
 * JSONL session metadata (aligned with pi {@code JsonlSessionMetadata}).
 *
 * @param id                       session id
 * @param createdAt                creation time
 * @param parentSessionId          may be null
 * @param cwd                      the session working directory
 * @param path                     the JSONL file path
 * @param modifiedAtMs             file modification time (epoch ms)
 * @param legacyFormat             {@code true} when the file carries this repo's **old** header
 *                                 ({@code kind:"header"}, version 3 or 4) and therefore needs
 *                                 migrating to pi's v3 wire; {@code false} for the current
 *                                 pi v3 wire ({@code type:"session"}, {@code docs/12})
 * @param legacyParentSessionPath  parent path when it cannot resolve to an id
 * @param metadata                 application metadata, may be null
 */
public record JsonlSessionMetadata(
    String id,
    Instant createdAt,
    String parentSessionId,
    String cwd,
    Path path,
    long modifiedAtMs,
    boolean legacyFormat,
    String legacyParentSessionPath,
    Map<String, Object> metadata
) implements SessionMetadata {
}
