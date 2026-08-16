package com.pijava.session.sqlite.storage;

import java.util.List;
import java.util.Optional;

import com.pijava.session.sqlite.SqliteDatabase;

/** branch_tips row operations (aligned with pi {@code branch-tips.ts}). */
public final class BranchTipRows {

    private BranchTipRows() {}

    /** Read all branch tip IDs for the session. */
    public static List<String> readBranchTipIds(SqliteDatabase db, String sessionId) {
        return db.all("SELECT tip_id FROM branch_tips WHERE session_id = ? ORDER BY tip_id",
            rs -> rs.getString("tip_id"), sessionId);
    }

    /** Read the branch ID of the tip {@code tipId}, if any. */
    public static Optional<String> readBranchTipBranchId(SqliteDatabase db, String sessionId,
                                                         String tipId) {
        return db.get("SELECT branch_id FROM branch_tips WHERE session_id = ? AND tip_id = ?",
            rs -> rs.getString("branch_id"), sessionId, tipId);
    }

    /** Insert a branch tip pointing at {@code tipId}. */
    public static void insertBranchTip(SqliteDatabase db, String sessionId, String tipId,
                                       String branchId) {
        db.run("INSERT INTO branch_tips (session_id, tip_id, branch_id) VALUES (?, ?, ?)",
            sessionId, tipId, branchId);
    }

    /** CAS update: returns false when the old tip changed concurrently. */
    public static boolean updateBranchTip(SqliteDatabase db, String sessionId, String branchId,
                                          String oldTipId, String newTipId) {
        int changes = db.run("""
            UPDATE branch_tips SET tip_id = ?
            WHERE session_id = ? AND branch_id = ? AND tip_id = ?
            """, newTipId, sessionId, branchId, oldTipId);
        return changes == 1;
    }

    /** Delete all branch tips for the session. */
    public static void deleteBranchTips(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM branch_tips WHERE session_id = ?", sessionId);
    }
}
