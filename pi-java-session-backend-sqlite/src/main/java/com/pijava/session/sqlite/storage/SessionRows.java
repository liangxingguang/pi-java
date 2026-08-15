package com.pijava.session.sqlite.storage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.session.sqlite.SqliteDatabase;
import com.pijava.session.sqlite.SqliteSessionMetadata;

/** Sessions-table row operations (aligned with pi {@code storage/sessions.ts}). */
public final class SessionRows {

    private SessionRows() {}

    /** Session row projected with the latest name fact. */
    public record SessionRow(
        String id,
        String createdAt,
        String metadata,
        String cwd,
        String parentSessionId,
        int hasSessionName,
        String sessionName
    ) {}

    public static boolean sessionExists(SqliteDatabase db, String sessionId) {
        return db.get("SELECT 1 AS found FROM sessions WHERE id = ?", rs -> rs.getInt("found"),
            sessionId).isPresent();
    }

    public static void insertSessionRow(SqliteDatabase db, NewSessionRow session) {
        db.run("""
            INSERT INTO sessions (id, created_at, metadata, cwd, parent_session_id)
            VALUES (?, ?, ?, ?, ?)
            """, session.id(), session.createdAt(), session.metadata(),
            session.cwd(), session.parentSessionId());
    }

    public static Optional<SessionRow> readSessionRow(SqliteDatabase db, String sessionId) {
        return db.get(SESSION_SELECT + " WHERE s.id = ?", SessionRows::mapSessionRow, sessionId);
    }

    public static List<SessionRow> readSessionRows(SqliteDatabase db, String cwd) {
        if (cwd == null) {
            return db.all(SESSION_SELECT + " ORDER BY s.created_at DESC", SessionRows::mapSessionRow);
        }
        return db.all(SESSION_SELECT + " WHERE s.cwd = ? ORDER BY s.created_at DESC",
            SessionRows::mapSessionRow, cwd);
    }

    public static void deleteSessionRow(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM sessions WHERE id = ?", sessionId);
    }

    public static SqliteSessionMetadata decodeSessionMetadata(SessionRow row, Path path) {
        return new SqliteSessionMetadata(
            row.id(),
            Instant.parse(row.createdAt()),
            row.parentSessionId(),
            row.cwd(),
            path,
            row.hasSessionName() == 0 ? null : parseSessionName(row.sessionName(), row.id()),
            parseMetadata(row.metadata(), row.id()));
    }

    private static final String SESSION_SELECT = """
        SELECT s.id, s.created_at, s.metadata, s.cwd, s.parent_session_id,
            name_fact.seq IS NOT NULL AS has_session_name,
            name_fact.value AS session_name
        FROM sessions AS s
        LEFT JOIN facts AS name_fact
            ON name_fact.session_id = s.id
            AND name_fact.kind = 'name'
            AND name_fact.key IS NULL
            AND name_fact.seq = (
                SELECT MAX(f.seq)
                FROM facts AS f
                WHERE f.session_id = s.id AND f.kind = 'name' AND f.key IS NULL
            )
        """;

    /** Public row mapper (reused by the search backend). */
    public static SessionRow mapSessionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionRow(
            rs.getString("id"),
            rs.getString("created_at"),
            rs.getString("metadata"),
            rs.getString("cwd"),
            rs.getString("parent_session_id"),
            rs.getInt("has_session_name"),
            rs.getString("session_name"));
    }

    /** New-session insert parameters. */
    public record NewSessionRow(
        String id,
        String createdAt,
        String cwd,
        String parentSessionId,
        String metadata
    ) {}

    private static String parseSessionName(String value, String sessionId) {
        try {
            var node = com.pijava.agent.session.SessionJson.mapper().readTree(value);
            return node != null && node.isTextual() ? node.textValue() : null;
        } catch (Exception e) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Invalid SQLite session " + sessionId + ": name is not valid JSON", e);
        }
    }

    private static java.util.Map<String, Object> parseMetadata(String value, String sessionId) {
        if (value == null) {
            return null;
        }
        try {
            var node = com.pijava.agent.session.SessionJson.mapper().readTree(value);
            if (node == null || !node.isObject()) {
                throw new SessionError(SessionErrorCode.STORAGE,
                    "Invalid SQLite session " + sessionId + ": metadata must be an object");
            }
            return com.pijava.agent.session.SessionJson.mapper()
                .convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (SessionError e) {
            throw e;
        } catch (Exception e) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Invalid SQLite session " + sessionId + ": metadata is not valid JSON", e);
        }
    }
}