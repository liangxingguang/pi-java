package com.pijava.session.sqlite.storage;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.RecordQuery;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionJson;
import com.pijava.agent.session.jsonl.JsonlCodec;
import com.pijava.session.sqlite.SqliteDatabase;

/** Records-table row operations (aligned with pi {@code storage/records.ts}). */
public final class RecordRows {

    private RecordRows() {}

    /** A persisted record row. */
    public record RecordRow(
        String sessionId,
        long seq,
        String id,
        String lane,
        String runId,
        String type,
        String opKind,
        String timestamp,
        String payload
    ) {}

    /** Append a new record row. */
    public static void appendRecordRow(SqliteDatabase db, String sessionId, NewRecordRow record) {
        db.run("""
            INSERT INTO records
                (session_id, seq, id, lane, run_id, type, op_kind, timestamp, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, sessionId, record.seq(), record.id(), record.lane(), record.runId(),
            record.type(), record.opKind(), record.timestamp(), record.payload());
    }

    /** Whether a record with the given ID exists in the session. */
    public static boolean idExistsInRecords(SqliteDatabase db, String sessionId, String id) {
        return db.get("SELECT 1 AS found FROM records WHERE session_id = ? AND id = ? LIMIT 1",
            rs -> rs.getInt("found"), sessionId, id).isPresent();
    }

    /** Delete all record rows for the session. */
    public static void deleteRecordRows(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM records WHERE session_id = ?", sessionId);
    }

    /** Read record rows per a {@link RecordQuery}. */
    public static List<RecordRow> readRecordRows(SqliteDatabase db, String sessionId,
                                                 RecordQuery query) {
        var sql = new StringBuilder(RECORD_SELECT).append(" WHERE session_id = ?");
        var params = new java.util.ArrayList<Object>();
        params.add(sessionId);
        if (query.lane() != null) {
            sql.append(" AND lane = ?");
            params.add(query.lane());
        }
        if (query.type() != null) {
            sql.append(" AND type = ?");
            params.add(query.type());
        }
        if (query.runId() != null) {
            sql.append(" AND run_id = ?");
            params.add(query.runId());
        }
        if (query.operationKind() != null) {
            sql.append(" AND op_kind = ?");
            params.add(query.operationKind().value());
        }
        if (query.afterSeq() != null) {
            sql.append(" AND seq > ?");
            params.add(query.afterSeq());
        }
        boolean oldestFirst = query.order() == EntryOrder.OLDEST_FIRST;
        sql.append(oldestFirst ? " ORDER BY seq ASC" : " ORDER BY seq DESC");
        if (query.limit() != null) {
            sql.append(" LIMIT ?");
            params.add(query.limit());
        }
        return db.all(sql.toString(), RecordRows::map, params.toArray());
    }

    /** Read the lane's open operation record (single row). */
    public static List<RecordRow> readOpenOperationRows(SqliteDatabase db, String sessionId,
                                                        String lane, Integer limit) {
        var openId = db.get("SELECT open_operation_id FROM lanes WHERE session_id = ? AND lane = ?",
            rs -> rs.getString("open_operation_id"), sessionId, lane);
        if (openId.isEmpty() || openId.get() == null) {
            return List.of();
        }
        var record = db.get(RECORD_SELECT + " WHERE session_id = ? AND id = ?",
            RecordRows::map, sessionId, openId.get());
        if (record.isEmpty()) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Lane " + lane + " points at missing open operation " + openId.get());
        }
        var row = record.get();
        if (!row.lane().equals(lane) || !"operation_started".equals(row.type())) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Lane " + lane + " points at invalid open operation " + openId.get());
        }
        return limit != null && limit <= 0 ? List.of() : List.of(row);
    }

    /** Decode a record row into a typed record. */
    public static LaneRecord decodeRecord(RecordRow row) {
        try {
            JsonNode payload = SessionJson.mapper().readTree(row.payload());
            return JsonlCodec.decodeRecordPayload(payload, row.id(), row.seq(),
                row.lane(), Instant.parse(row.timestamp()));
        } catch (Exception e) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Invalid SQLite session record at sequence " + row.seq()
                    + ": failed to decode payload", e);
        }
    }

    /** Insert-parameter carrier. */
    public record NewRecordRow(
        long seq,
        String id,
        String lane,
        String runId,
        String type,
        String opKind,
        String timestamp,
        String payload
    ) {}

    private static final String RECORD_SELECT = """
        SELECT session_id, seq, id, lane, run_id, type, op_kind, timestamp, payload
        FROM records
        """;

    private static RecordRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RecordRow(
            rs.getString("session_id"),
            rs.getLong("seq"),
            rs.getString("id"),
            rs.getString("lane"),
            rs.getString("run_id"),
            rs.getString("type"),
            rs.getString("op_kind"),
            rs.getString("timestamp"),
            rs.getString("payload"));
    }
}
