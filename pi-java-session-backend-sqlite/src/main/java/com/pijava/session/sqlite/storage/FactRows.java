package com.pijava.session.sqlite.storage;

import java.util.List;
import java.util.Optional;

import com.pijava.session.sqlite.SqliteDatabase;

/** Facts-table row operations (aligned with pi {@code storage/facts.ts}). */
public final class FactRows {

    private FactRows() {}

    /** A fact row. */
    public record FactRow(
        String sessionId,
        long seq,
        String kind,
        String key,
        String value
    ) {}

    public static void appendFact(SqliteDatabase db, String sessionId, long seq,
                                  String kind, String key, String value) {
        db.run("INSERT INTO facts (session_id, seq, kind, key, value) VALUES (?, ?, ?, ?, ?)",
            sessionId, seq, kind, key, value);
    }

    public static Optional<FactRow> readLatestFact(SqliteDatabase db, String sessionId,
                                                   String kind, String key) {
        return db.get("""
            SELECT session_id, seq, kind, key, value
            FROM facts INDEXED BY idx_facts_session_kind_key_seq
            WHERE session_id = ? AND kind = ? AND key IS ?
            ORDER BY seq DESC
            LIMIT 1
            """, FactRows::map, sessionId, kind, key);
    }

    /** Latest non-null label facts. */
    public static List<String[]> readLatestLabelFacts(SqliteDatabase db, String sessionId) {
        return db.all("""
            SELECT f.key, f.value
            FROM facts AS f INDEXED BY idx_facts_session_kind_key_seq
            WHERE f.session_id = ?
                AND f.kind = 'label'
                AND f.value IS NOT NULL
                AND f.seq = (
                    SELECT MAX(candidate.seq)
                    FROM facts AS candidate INDEXED BY idx_facts_session_kind_key_seq
                    WHERE candidate.session_id = f.session_id
                        AND candidate.kind = f.kind
                        AND candidate.key IS f.key
                )
            ORDER BY f.key
            """, rs -> new String[] { rs.getString("key"), rs.getString("value") }, sessionId);
    }

    public static List<FactRow> readFactRows(SqliteDatabase db, String sessionId,
                                             Long afterSeq, Integer limit) {
        var sql = new StringBuilder("SELECT session_id, seq, kind, key, value FROM facts WHERE session_id = ?");
        var params = new java.util.ArrayList<Object>();
        params.add(sessionId);
        if (afterSeq != null) {
            sql.append(" AND seq > ?");
            params.add(afterSeq);
        }
        sql.append(" ORDER BY seq");
        if (limit != null) {
            sql.append(" LIMIT ?");
            params.add(limit);
        }
        return db.all(sql.toString(), FactRows::map, params.toArray());
    }

    public static void deleteFactRows(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM facts WHERE session_id = ?", sessionId);
    }

    private static FactRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FactRow(
            rs.getString("session_id"),
            rs.getLong("seq"),
            rs.getString("kind"),
            rs.getString("key"),
            rs.getString("value"));
    }
}
