package com.pijava.session.sqlite.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.EntryCursor;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.SessionJson;
import com.pijava.agent.session.jsonl.JsonlCodec;
import com.pijava.session.sqlite.SqliteDatabase;

/** Entries-table row operations (aligned with pi {@code storage/entries.ts}). */
public final class EntryRows {

    private EntryRows() {}

    /** A persisted entry row. */
    public record EntryRow(
        String sessionId,
        long seq,
        String id,
        String parentId,
        String type,
        String timestamp,
        String payload
    ) {}

    /** Strip the identity fields from an entry, producing the payload JSON. */
    public static String entryPayload(Entry entry) {
        try {
            JsonNode node = SessionJson.mapper().valueToTree(entry);
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            object.remove("id");
            object.remove("seq");
            object.remove("parentId");
            object.remove("timestamp");
            object.remove("type");
            return SessionJson.mapper().writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode entry payload", e);
        }
    }

    /** Insert a new entry row. */
    public static void insertEntryRow(SqliteDatabase db, String sessionId, NewEntryRow entry) {
        db.run("""
            INSERT INTO entries (session_id, id, seq, parent_id, type, timestamp, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, sessionId, entry.id(), entry.seq(), entry.parentId(), entry.type(),
            entry.timestamp(), entry.payload());
    }

    public static Optional<EntryRow> readEntryRow(SqliteDatabase db, String sessionId, String entryId) {
        return db.get(ENTRY_SELECT + " WHERE session_id = ? AND id = ?",
            EntryRows::map, sessionId, entryId);
    }

    /** Read entry rows with optional filters. */
    public static List<EntryRow> readEntryRows(SqliteDatabase db, String sessionId,
                                               QueryOptions options) {
        StringBuilder sql = new StringBuilder(ENTRY_SELECT).append(" WHERE session_id = ?");
        var params = new java.util.ArrayList<Object>();
        params.add(sessionId);
        boolean oldestFirst = options.order() == EntryOrder.OLDEST_FIRST;
        if (options.afterSeq() != null) {
            sql.append(" AND seq > ?");
            params.add(options.afterSeq());
        }
        if (options.cursor() != null) {
            sql.append(oldestFirst ? " AND seq > ?" : " AND seq < ?");
            params.add(options.cursor().afterSeq());
        }
        if (options.type() != null) {
            sql.append(" AND type = ?");
            params.add(options.type());
        }
        sql.append(oldestFirst ? " ORDER BY seq ASC" : " ORDER BY seq DESC");
        if (options.limit() != null) {
            sql.append(" LIMIT ?");
            params.add(options.limit());
        }
        return db.all(sql.toString(), EntryRows::map, params.toArray());
    }

    public static boolean idExistsInEntries(SqliteDatabase db, String sessionId, String id) {
        return db.get("SELECT 1 AS found FROM entries WHERE session_id = ? AND id = ? LIMIT 1",
            rs -> rs.getInt("found"), sessionId, id).isPresent();
    }

    public static void deleteEntryRows(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM entries WHERE session_id = ?", sessionId);
    }

    /** Decode an entry row into a typed entry. */
    public static Entry decodeEntry(EntryRow row) {
        try {
            JsonNode payload = SessionJson.mapper().readTree(row.payload());
            return JsonlCodec.decodeEntryPayload(payload, row.id(), row.seq(),
                row.parentId(), Instant.parse(row.timestamp()), row.type());
        } catch (Exception e) {
            throw new com.pijava.agent.session.SessionError(
                com.pijava.agent.session.SessionErrorCode.INVALID_ENTRY,
                "Invalid SQLite session entry " + row.id() + ": failed to decode entry", e);
        }
    }

    /** Insert-parameter carrier. */
    public record NewEntryRow(
        long seq,
        String id,
        String parentId,
        String type,
        String timestamp,
        String payload
    ) {}

    /** Read options. */
    public record QueryOptions(
        Long afterSeq,
        EntryCursor cursor,
        String type,
        EntryOrder order,
        Integer limit
    ) {

        public static QueryOptions of(Long afterSeq, EntryCursor cursor, String type,
                                      EntryOrder order, Integer limit) {
            return new QueryOptions(afterSeq, cursor, type, order, limit);
        }
    }

    private static final String ENTRY_SELECT = """
        SELECT session_id, seq, id, parent_id, type, timestamp, payload
        FROM entries
        """;

    private static EntryRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EntryRow(
            rs.getString("session_id"),
            rs.getLong("seq"),
            rs.getString("id"),
            rs.getString("parent_id"),
            rs.getString("type"),
            rs.getString("timestamp"),
            rs.getString("payload"));
    }
}