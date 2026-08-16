package com.pijava.session.sqlite.storage;

import java.util.Optional;

import com.pijava.session.sqlite.SqliteDatabase;
import com.pijava.session.sqlite.WriterLease;

/** Writer-lease row operations (aligned with pi {@code writer-leases.ts}). */
public final class WriterLeaseRows {

    private WriterLeaseRows() {}

    /** Insert or preempt (fence+1) an expired lease. */
    public static Optional<WriterLease> acquire(SqliteDatabase db, String sessionId,
                                                String ownerId, long now, long expiresAtMs) {
        // Explicit transaction: RETURNING writes must commit for cross-connection visibility.
        return db.transaction(() -> db.get("""
            INSERT INTO writer_leases (session_id, owner_id, fence, expires_at_ms)
            VALUES (?, ?, 1, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                owner_id = excluded.owner_id,
                fence = writer_leases.fence + 1,
                expires_at_ms = excluded.expires_at_ms
            WHERE writer_leases.expires_at_ms <= ?
            RETURNING owner_id, fence, expires_at_ms
            """, rs -> new WriterLease(rs.getString("owner_id"), rs.getInt("fence"),
                rs.getLong("expires_at_ms")),
            sessionId, ownerId, expiresAtMs, now));
    }

    /** Renew when owner+fence match and the lease has not expired. */
    public static boolean renew(SqliteDatabase db, String sessionId, WriterLease lease,
                                long now, long expiresAtMs) {
        return db.transaction(() -> db.run("""
            UPDATE writer_leases SET expires_at_ms = ?
            WHERE session_id = ? AND owner_id = ? AND fence = ? AND expires_at_ms > ?
            """, expiresAtMs, sessionId, lease.ownerId(), lease.fence(), now)) == 1;
    }

    /** Release when owner+fence match. */
    public static void release(SqliteDatabase db, String sessionId, WriterLease lease) {
        db.transaction(() -> db.run(
            "DELETE FROM writer_leases WHERE session_id = ? AND owner_id = ? AND fence = ?",
            sessionId, lease.ownerId(), lease.fence()));
    }

    /** Delete any lease for the session. */
    public static void delete(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM writer_leases WHERE session_id = ?", sessionId);
    }
}
