package com.pijava.session.sqlite;

import java.util.List;

import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.UuidV7;
import com.pijava.session.sqlite.storage.BranchEntryRows;
import com.pijava.session.sqlite.storage.BranchTipRows;

/**
 * Derived branch cache (aligned with pi {@code branch-cache.ts}). The
 * canonical topology is {@code entries.parent_id}; this cache only makes
 * branch scans O(log n).
 */
public final class BranchCache {

    private BranchCache() {}

    /** Delete all cached branch tips and entries for the session. */
    public static void deleteBranchCache(SqliteDatabase db, String sessionId) {
        BranchTipRows.deleteBranchTips(db, sessionId);
        BranchEntryRows.deleteBranchEntries(db, sessionId);
    }

    /** Rebuild the cache from all leaf entries (SAVEPOINT-protected per leaf). */
    public static void rebuildBranchCache(SqliteDatabase db, String sessionId) {
        List<String> tips = db.all("""
            SELECT leaf.id
            FROM entries AS leaf
            WHERE leaf.session_id = ?
                AND NOT EXISTS (
                    SELECT 1 FROM entries AS child
                    WHERE child.session_id = leaf.session_id AND child.parent_id = leaf.id
                )
            ORDER BY leaf.seq
            """, rs -> rs.getString("id"), sessionId);
        deleteBranchCache(db, sessionId);
        for (var tip : tips) {
            buildCachedBranch(db, sessionId, tip);
        }
    }

    /** Build a new cached branch for the path from {@code leafId} to root, SAVEPOINT-protected. */
    public static void buildCachedBranch(SqliteDatabase db, String sessionId, String leafId) {
        db.exec("SAVEPOINT build_branch_cache");
        try {
            String branchId = UuidV7.INSTANCE.next();
            BranchEntryRows.insertBranchEntriesForPath(db, sessionId, branchId, leafId);
            BranchTipRows.insertBranchTip(db, sessionId, leafId, branchId);
            db.exec("RELEASE SAVEPOINT build_branch_cache");
        } catch (RuntimeException error) {
            try {
                db.exec("ROLLBACK TO SAVEPOINT build_branch_cache");
                db.exec("RELEASE SAVEPOINT build_branch_cache");
            } catch (RuntimeException ignored) {
                // Preserve the original build failure.
            }
            if (error instanceof SessionError) {
                throw error;
            }
            throw new SessionError(SessionErrorCode.STORAGE,
                "Failed to build SQLite branch cache at entry " + leafId, error);
        }
    }

    /** Incrementally extend or fork the cache for a new entry. */
    public static void appendEntryToBranchCache(SqliteDatabase db, String sessionId, String entryId,
                                                long entrySeq, String entryType, String customType,
                                                String parentId) {
        if (parentId == null) {
            String branchId = UuidV7.INSTANCE.next();
            BranchEntryRows.insertBranchEntry(db, sessionId, branchId, entryId, entrySeq,
                entryType, customType);
            BranchTipRows.insertBranchTip(db, sessionId, entryId, branchId);
            return;
        }
        var tipBranchId = BranchTipRows.readBranchTipBranchId(db, sessionId, parentId);
        if (tipBranchId.isPresent()) {
            extendBranch(db, sessionId, tipBranchId.get(), parentId, entryId, entrySeq,
                entryType, customType);
            return;
        }
        var source = BranchEntryRows.readBranchContainingEntry(db, sessionId, parentId);
        if (source.isEmpty()) {
            throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                "Branch cache has no branch containing parent entry " + parentId);
        }
        String branchId = UuidV7.INSTANCE.next();
        BranchEntryRows.copyBranchEntriesThroughSeq(db, sessionId, branchId,
            source.get().branchId(), source.get().leafSeq());
        BranchEntryRows.insertBranchEntry(db, sessionId, branchId, entryId, entrySeq,
            entryType, customType);
        BranchTipRows.insertBranchTip(db, sessionId, entryId, branchId);
    }

    private static void extendBranch(SqliteDatabase db, String sessionId, String branchId,
                                     String parentId, String entryId, long entrySeq,
                                     String entryType, String customType) {
        BranchEntryRows.insertBranchEntry(db, sessionId, branchId, entryId, entrySeq,
            entryType, customType);
        if (!BranchTipRows.updateBranchTip(db, sessionId, branchId, parentId, entryId)) {
            throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                "Branch tip " + parentId + " changed during append");
        }
    }
}
