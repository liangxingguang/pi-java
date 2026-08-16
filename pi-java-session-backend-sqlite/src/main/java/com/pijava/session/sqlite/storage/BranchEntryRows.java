package com.pijava.session.sqlite.storage;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionJson;
import com.pijava.session.sqlite.SqliteDatabase;

/** branch_entries row operations (aligned with pi {@code branch-entries.ts}). */
public final class BranchEntryRows {

    private BranchEntryRows() {}

    /** A cached-branch membership row. */
    public record CachedBranch(String branchId, long leafSeq) {}

    /** A branch-path entry row (for decode). */
    public record CachedBranchEntryRow(
        String sessionId,
        String id,
        long entrySeq,
        String parentId,
        String type,
        String timestamp,
        String payload
    ) {}

    public static Optional<CachedBranch> readCachedBranch(SqliteDatabase db, String sessionId,
                                                          String leafId) {
        return db.get("""
            SELECT branch_id, entry_seq
            FROM branch_entries
            WHERE session_id = ? AND entry_id = ?
            ORDER BY branch_id
            LIMIT 1
            """, rs -> new CachedBranch(rs.getString("branch_id"), rs.getLong("entry_seq")),
            sessionId, leafId);
    }

    /** Query cached branch rows with stop/cursor/type/limit filters. */
    public static List<CachedBranchEntryRow> queryCachedBranchRows(
            SqliteDatabase db, String sessionId, CachedBranch branch, Query query) {
        boolean oldestFirst = query.order() == com.pijava.agent.session.EntryOrder.OLDEST_FIRST;
        var stopPredicates = new java.util.ArrayList<String>();
        var stopParams = new java.util.ArrayList<Object>();
        if (query.stopAtType() != null) {
            stopPredicates.add("stop.entry_type = ?");
            stopParams.add(query.stopAtType());
        }
        if (query.stopAtId() != null) {
            stopPredicates.add("stop.entry_id = ?");
            stopParams.add(query.stopAtId());
        }

        String aggregate = oldestFirst ? "MIN" : "MAX";
        String boundaryComparison = oldestFirst ? "<=" : ">=";
        String cursorComparison = oldestFirst ? ">" : "<";
        String direction = oldestFirst ? "ASC" : "DESC";

        var sql = new StringBuilder("""
            SELECT e.session_id, e.id, e.seq AS entry_seq, e.parent_id, e.type, e.timestamp, e.payload
            FROM branch_entries AS b
            JOIN entries AS e ON e.session_id = b.session_id AND e.id = b.entry_id
            WHERE b.session_id = ? AND b.branch_id = ? AND b.entry_seq <= ?
            """);
        var params = new java.util.ArrayList<Object>();
        params.add(sessionId);
        params.add(branch.branchId());
        params.add(branch.leafSeq());

        if (!stopPredicates.isEmpty()) {
            sql.append(" AND b.entry_seq ").append(boundaryComparison).append(" COALESCE((");
            sql.append("SELECT ").append(aggregate).append("(stop.entry_seq) FROM branch_entries AS stop ");
            sql.append("WHERE stop.session_id = ? AND stop.branch_id = ? AND stop.entry_seq <= ? AND (");
            sql.append(String.join(" OR ", stopPredicates)).append(")), ");
            sql.append(oldestFirst ? branch.leafSeq() : 0).append(")");
            params.add(sessionId);
            params.add(branch.branchId());
            params.add(branch.leafSeq());
            params.addAll(stopParams);
        }
        if (query.cursor() != null) {
            sql.append(" AND b.entry_seq ").append(cursorComparison).append(" ?");
            params.add(query.cursor().afterSeq());
        }
        if (query.type() != null) {
            sql.append(" AND b.entry_type = ?");
            params.add(query.type());
        }
        if (query.customType() != null) {
            sql.append(" AND b.custom_type = ?");
            params.add(query.customType());
        }
        sql.append(" ORDER BY b.entry_seq ").append(direction);
        if (query.limit() != null) {
            sql.append(" LIMIT ?");
            params.add(query.limit());
        }
        return db.all(sql.toString(), BranchEntryRows::map, params.toArray());
    }

    /** Query carrier. */
    public record Query(
        String type,
        String customType,
        String stopAtType,
        String stopAtId,
        com.pijava.agent.session.EntryCursor cursor,
        com.pijava.agent.session.EntryOrder order,
        Integer limit
    ) {}

    public static void deleteBranchEntries(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM branch_entries WHERE session_id = ?", sessionId);
    }

    public static void insertBranchEntry(SqliteDatabase db, String sessionId, String branchId,
                                         String entryId, long entrySeq, String entryType,
                                         String customType) {
        db.run("""
            INSERT INTO branch_entries
                (session_id, branch_id, entry_id, entry_seq, entry_type, custom_type)
            VALUES (?, ?, ?, ?, ?, ?)
            """, sessionId, branchId, entryId, entrySeq, entryType, customType);
    }

    /** Insert the whole path from {@code leafId} to root under {@code branchId}. */
    public static void insertBranchEntriesForPath(SqliteDatabase db, String sessionId,
                                                  String branchId, String leafId) {
        var path = new java.util.ArrayList<String[]>();
        var seen = new java.util.HashSet<String>();
        String entryId = leafId;
        while (entryId != null) {
            if (!seen.add(entryId)) {
                throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                    "Entry parent cycle at " + entryId);
            }
            var row = db.get("SELECT id, seq, parent_id, type, payload FROM entries WHERE session_id = ? AND id = ?",
                rs -> new String[] { rs.getString("id"), String.valueOf(rs.getLong("seq")),
                    rs.getString("parent_id"), rs.getString("type"), rs.getString("payload") },
                sessionId, entryId);
            if (row.isEmpty()) {
                throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                    "Entry " + entryId + " not found");
            }
            path.add(row.get());
            entryId = row.get()[2];
        }
        java.util.Collections.reverse(path);
        for (var row : path) {
            insertBranchEntry(db, sessionId, branchId, row[0], Long.parseLong(row[1]),
                row[3], customTypeFromPayload(row[4], row[0]));
        }
    }

    public static Optional<CachedBranch> readBranchContainingEntry(SqliteDatabase db,
                                                                   String sessionId, String entryId) {
        return db.get("""
            SELECT b.branch_id, b.entry_seq
            FROM branch_entries AS b
            WHERE b.session_id = ? AND b.entry_id = ?
            ORDER BY b.branch_id
            LIMIT 1
            """, rs -> new CachedBranch(rs.getString("branch_id"), rs.getLong("entry_seq")),
            sessionId, entryId);
    }

    public static void copyBranchEntriesThroughSeq(SqliteDatabase db, String sessionId,
                                                   String targetBranchId, String sourceBranchId,
                                                   long throughSeq) {
        db.run("""
            INSERT INTO branch_entries (session_id, branch_id, entry_id, entry_seq, entry_type, custom_type)
            SELECT session_id, ?, entry_id, entry_seq, entry_type, custom_type
            FROM branch_entries
            WHERE session_id = ? AND branch_id = ? AND entry_seq <= ?
            """, targetBranchId, sessionId, sourceBranchId, throughSeq);
    }

    private static String customTypeFromPayload(String payload, String id) {
        try {
            JsonNode node = SessionJson.mapper().readTree(payload);
            if (node == null || !node.isObject() || !node.has("customType") || !node.get("customType").isTextual()) {
                return null;
            }
            return node.get("customType").textValue();
        } catch (Exception e) {
            throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                "Invalid SQLite session entry " + id + ": failed to decode entry", e);
        }
    }

    private static CachedBranchEntryRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CachedBranchEntryRow(
            rs.getString("session_id"),
            rs.getString("id"),
            rs.getLong("entry_seq"),
            rs.getString("parent_id"),
            rs.getString("type"),
            rs.getString("timestamp"),
            rs.getString("payload"));
    }
}
