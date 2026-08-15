package com.pijava.session.sqlite.storage;

import java.util.List;
import java.util.Optional;

import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.session.sqlite.SqliteDatabase;

/** Lanes/lane_moves row operations (aligned with pi {@code storage/lanes.ts}). */
public final class LaneRows {

    private LaneRows() {}

    /** A lane row. */
    public record LaneRow(
        String sessionId,
        String lane,
        String leafId,
        String openOperationId
    ) {}

    /** A lane-move log row. */
    public record LaneMoveRow(
        String sessionId,
        long seq,
        String lane,
        String leafId
    ) {}

    public static void createInitialLane(SqliteDatabase db, String sessionId,
                                         String lane, String leafId) {
        db.run("INSERT INTO lanes (session_id, lane, leaf_id, open_operation_id) VALUES (?, ?, ?, NULL)",
            sessionId, lane, leafId);
    }

    /** Read all lanes, verifying each leaf exists (else {@code storage}). */
    public static List<LaneRow> readLanes(SqliteDatabase db, String sessionId) {
        var rows = db.all("""
            SELECT l.session_id, l.lane, l.leaf_id, l.open_operation_id,
                (l.leaf_id IS NULL OR EXISTS (
                    SELECT 1 FROM entries AS e
                    WHERE e.session_id = l.session_id AND e.id = l.leaf_id
                )) AS leaf_exists
            FROM lanes AS l
            WHERE l.session_id = ?
            ORDER BY l.lane
            """, rs -> new LaneRow(rs.getString("session_id"), rs.getString("lane"),
                rs.getString("leaf_id"), rs.getString("open_operation_id")), sessionId);
        for (var row : rows) {
            // leaf existence is verified by the SQL; map returns rows without it,
            // so re-query the exists flag for strictness.
            var exists = db.get("""
                SELECT (l.leaf_id IS NULL OR EXISTS (
                    SELECT 1 FROM entries AS e
                    WHERE e.session_id = l.session_id AND e.id = l.leaf_id
                )) AS ok FROM lanes AS l WHERE l.session_id = ? AND l.lane = ?
                """, rs -> rs.getInt("ok"), sessionId, row.lane());
            if (exists.isPresent() && exists.get() == 0) {
                throw new SessionError(SessionErrorCode.STORAGE,
                    "Lane " + row.lane() + " points at missing entry " + row.leafId());
            }
        }
        return rows;
    }

    public static Optional<LaneRow> readLane(SqliteDatabase db, String sessionId, String lane) {
        return db.get("SELECT session_id, lane, leaf_id, open_operation_id FROM lanes WHERE session_id = ? AND lane = ?",
            rs -> new LaneRow(rs.getString("session_id"), rs.getString("lane"),
                rs.getString("leaf_id"), rs.getString("open_operation_id")),
            sessionId, lane);
    }

    /** Read the lane head, throwing {@code invalid_lane} when the lane is missing. */
    public static String readLaneHead(SqliteDatabase db, String sessionId, String lane) {
        var row = db.get("""
            SELECT l.leaf_id,
                (l.leaf_id IS NULL OR EXISTS (
                    SELECT 1 FROM entries AS e
                    WHERE e.session_id = l.session_id AND e.id = l.leaf_id
                )) AS leaf_exists
            FROM lanes AS l
            WHERE l.session_id = ? AND l.lane = ?
            """, rs -> new String[] { rs.getString("leaf_id"), String.valueOf(rs.getInt("leaf_exists")) },
            sessionId, lane);
        if (row.isEmpty()) {
            throw new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: " + lane);
        }
        if ("0".equals(row.get()[1])) {
            throw new SessionError(SessionErrorCode.STORAGE, "Entry " + row.get()[0] + " not found");
        }
        return row.get()[0];
    }

    public static void createLane(SqliteDatabase db, String sessionId, long seq,
                                  String lane, String leafId) {
        db.run("INSERT INTO lanes (session_id, lane, leaf_id, open_operation_id) VALUES (?, ?, ?, NULL)",
            sessionId, lane, leafId);
        appendLaneMove(db, sessionId, seq, lane, leafId);
    }

    public static void moveLane(SqliteDatabase db, String sessionId, long seq,
                                String lane, String leafId) {
        int changes = db.run("UPDATE lanes SET leaf_id = ? WHERE session_id = ? AND lane = ?",
            leafId, sessionId, lane);
        if (changes != 1) {
            throw new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: " + lane);
        }
        appendLaneMove(db, sessionId, seq, lane, leafId);
    }

    public static void setLaneLeaf(SqliteDatabase db, String sessionId, String lane, String leafId) {
        int changes = db.run("UPDATE lanes SET leaf_id = ? WHERE session_id = ? AND lane = ?",
            leafId, sessionId, lane);
        if (changes != 1) {
            throw new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: " + lane);
        }
    }

    /** Open an operation on a lane; throws {@code storage} when one is already open. */
    public static void startLaneOperation(SqliteDatabase db, String sessionId, String lane, String runId) {
        int changes = db.run("""
            UPDATE lanes SET open_operation_id = ?
            WHERE session_id = ? AND lane = ? AND open_operation_id IS NULL
            """, runId, sessionId, lane);
        if (changes == 1) {
            return;
        }
        var current = readLane(db, sessionId, lane);
        if (current.isEmpty()) {
            throw new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: " + lane);
        }
        throw new SessionError(SessionErrorCode.STORAGE,
            "Lane " + lane + " already has an open operation " + current.get().openOperationId());
    }

    public static void finishLaneOperation(SqliteDatabase db, String sessionId, String lane, String runId) {
        db.run("UPDATE lanes SET open_operation_id = NULL WHERE session_id = ? AND lane = ? AND open_operation_id = ?",
            sessionId, lane, runId);
    }

    public static List<LaneMoveRow> readLaneMoveRows(SqliteDatabase db, String sessionId,
                                                     Long afterSeq, Integer limit) {
        var sql = new StringBuilder(
            "SELECT session_id, seq, lane, leaf_id FROM lane_moves WHERE session_id = ?");
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
        return db.all(sql.toString(), rs -> new LaneMoveRow(
            rs.getString("session_id"), rs.getLong("seq"),
            rs.getString("lane"), rs.getString("leaf_id")), params.toArray());
    }

    public static void deleteLaneRows(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM lane_moves WHERE session_id = ?", sessionId);
        db.run("DELETE FROM lanes WHERE session_id = ?", sessionId);
    }

    private static void appendLaneMove(SqliteDatabase db, String sessionId, long seq,
                                       String lane, String leafId) {
        db.run("INSERT INTO lane_moves (session_id, seq, lane, leaf_id) VALUES (?, ?, ?, ?)",
            sessionId, seq, lane, leafId);
    }
}