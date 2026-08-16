package com.pijava.session.sqlite;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.pijava.agent.session.SessionSearch;
import com.pijava.agent.session.SessionSearchHit;
import com.pijava.agent.session.SessionSearchOptions;
import com.pijava.session.sqlite.storage.SessionRows;

/**
 * FTS5 full-text search over the canonical session database (aligned with pi
 * {@code search-backend.ts}). Uses the external-content {@code session_search_fts}
 * table with trigram tokenization and bm25 ranking.
 */
public final class SqliteSessionSearch implements SessionSearch<SqliteSessionMetadata> {

    private final Path databasePath;
    private SqliteDatabase database;

    /** Create a search instance over the session database at {@code databasePath}. */
    public SqliteSessionSearch(Path databasePath) {
        this.databasePath = databasePath;
    }

    /** Open a search instance, creating the FTS5 schema if needed. */
    public static SqliteSessionSearch create(Path databasePath) {
        var search = new SqliteSessionSearch(databasePath);
        search.openDatabase();
        return search;
    }

    @Override
    public List<SessionSearchHit<SqliteSessionMetadata>> search(SessionSearchOptions options) {
        String text = options.text().trim();
        if (text.isEmpty()) {
            return List.of();
        }
        var db = openDatabase();
        try {
            String query = "\"" + text.replace("\"", "\"\"") + "\"";
            String cwd = options.cwd();
            var rows = db.all("""
                SELECT s.id, s.created_at, s.metadata, s.cwd, s.parent_session_id,
                    name_fact.seq IS NOT NULL AS has_session_name,
                    name_fact.value AS session_name,
                    se.id AS entry_id, se.timestamp, bm25(session_search_fts) AS score
                FROM session_search_fts
                JOIN entries AS se ON se.rowid = session_search_fts.rowid
                JOIN sessions AS s ON s.id = se.session_id
                LEFT JOIN facts AS name_fact
                    ON name_fact.session_id = s.id
                    AND name_fact.kind = 'name'
                    AND name_fact.key IS NULL
                    AND name_fact.seq = (
                        SELECT MAX(f.seq)
                        FROM facts AS f
                        WHERE f.session_id = s.id AND f.kind = 'name' AND f.key IS NULL
                    )
                WHERE session_search_fts MATCH ? AND (? IS NULL OR s.cwd = ?)
                ORDER BY score
                """, rs -> new SearchRow(
                    SessionRows.mapSessionRow(rs),
                    rs.getString("entry_id"),
                    rs.getString("timestamp"),
                    rs.getDouble("score")), query, cwd, cwd);
            return rows.stream()
                .map(row -> new SessionSearchHit<>(
                    SessionRows.decodeSessionMetadata(row.sessionRow(), databasePath),
                    row.entryId(),
                    Instant.parse(row.timestamp()),
                    null,
                    row.score()))
                .toList();
        } finally {
            db.close();
            database = null;
        }
    }

    @Override
    public void close() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private SqliteDatabase openDatabase() {
        if (database != null) {
            return database;
        }
        var db = SqliteDatabase.open(databasePath);
        try {
            db.exec("PRAGMA journal_mode=WAL");
            db.exec("PRAGMA synchronous=FULL");
            db.exec("PRAGMA busy_timeout=5000");
            Migrations.applyMigrations(db);
            ensureSearchSchema(db);
            database = db;
            return db;
        } catch (RuntimeException e) {
            db.close();
            throw e;
        }
    }

    private static void ensureSearchSchema(SqliteDatabase db) {
        boolean ftsExists = db.get(
            "SELECT 1 AS found FROM sqlite_master WHERE type = 'table' AND name = 'session_search_fts' LIMIT 1",
            rs -> rs.getInt("found")).isPresent();
        db.exec("""
            CREATE VIRTUAL TABLE IF NOT EXISTS session_search_fts USING fts5(
              payload,
              content = 'entries',
              content_rowid = 'rowid',
              tokenize = 'trigram remove_diacritics 1'
            );
            CREATE TRIGGER IF NOT EXISTS session_search_fts_ai AFTER INSERT ON entries BEGIN
              INSERT INTO session_search_fts(rowid, payload) VALUES (new.rowid, new.payload);
            END;
            CREATE TRIGGER IF NOT EXISTS session_search_fts_ad AFTER DELETE ON entries BEGIN
              INSERT INTO session_search_fts(session_search_fts, rowid, payload)
                VALUES('delete', old.rowid, old.payload);
            END;
            CREATE TRIGGER IF NOT EXISTS session_search_fts_au AFTER UPDATE OF payload ON entries BEGIN
              INSERT INTO session_search_fts(session_search_fts, rowid, payload)
                VALUES('delete', old.rowid, old.payload);
              INSERT INTO session_search_fts(rowid, payload) VALUES (new.rowid, new.payload);
            END;
            """);
        if (!ftsExists) {
            db.exec("INSERT INTO session_search_fts(session_search_fts) VALUES('rebuild')");
        }
    }

    private record SearchRow(SessionRows.SessionRow sessionRow, String entryId,
                             String timestamp, double score) {}
}
