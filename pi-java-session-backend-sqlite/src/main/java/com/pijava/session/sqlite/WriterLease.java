package com.pijava.session.sqlite;

import java.util.Optional;

import com.pijava.session.sqlite.storage.WriterLeaseRows;

/**
 * Per-session writer lease with fence-based preemption (aligned with pi
 * {@code storage/writer-leases.ts}).
 *
 * <p>TTL defaults to 30s, heartbeat to 10s. A new lease starts at fence 1;
 * preempting an expired lease atomically bumps the fence so the old holder's
 * renew/write fails.</p>
 *
 * @param ownerId    holder identity
 * @param fence      monotonic fence
 * @param expiresAtMs lease expiry (epoch ms)
 */
public record WriterLease(String ownerId, int fence, long expiresAtMs) {

    /**
     * Acquire (or preempt) the lease. Returns empty when an unexpired holder exists.
     *
     * @param now        current time (epoch ms)
     * @param ttlMs      lease time-to-live
     */
    public static Optional<WriterLease> acquire(SqliteDatabase db, String sessionId,
                                                String ownerId, long now, long ttlMs) {
        return WriterLeaseRows.acquire(db, sessionId, ownerId, now, now + ttlMs);
    }

    /**
     * Renew (heartbeat/write) with owner+fence+unexpired verification.
     * Returns false when the lease was lost.
     */
    public static boolean renew(SqliteDatabase db, String sessionId, WriterLease lease,
                                long now, long ttlMs) {
        return WriterLeaseRows.renew(db, sessionId, lease, now, now + ttlMs);
    }

    /** Release the lease (owner+fence matched). */
    public static void release(SqliteDatabase db, String sessionId, WriterLease lease) {
        WriterLeaseRows.release(db, sessionId, lease);
    }

    /** Delete any lease for the session (used by delete). */
    public static void delete(SqliteDatabase db, String sessionId) {
        WriterLeaseRows.delete(db, sessionId);
    }
}
