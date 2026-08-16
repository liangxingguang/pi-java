package com.pijava.session.sqlite.storage;

import com.pijava.ai.Usage;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionStats;
import com.pijava.session.sqlite.SqliteDatabase;

/** session_stats row operations (aligned with pi {@code session-stats.ts}). */
public final class StatsRows {

    private StatsRows() {}

    /** Create the stats row for a new session. */
    public static void createStats(SqliteDatabase db, String sessionId, long messageCount) {
        db.run("""
            INSERT INTO session_stats
                (session_id, message_count, cached_tokens, uncached_tokens, total_tokens, cost_total)
            VALUES (?, ?, 0, 0, 0, 0)
            """, sessionId, messageCount);
    }

    /** Read the session's usage stats. */
    public static SessionStats readStats(SqliteDatabase db, String sessionId) {
        var row = db.get("""
            SELECT session_id, message_count, cached_tokens, uncached_tokens, total_tokens, cost_total
            FROM session_stats
            WHERE session_id = ?
            """, rs -> new double[] {
                rs.getDouble("message_count"), rs.getDouble("cached_tokens"),
                rs.getDouble("uncached_tokens"), rs.getDouble("total_tokens"),
                rs.getDouble("cost_total") }, sessionId);
        if (row.isEmpty()) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Missing stats row for session " + sessionId);
        }
        var values = row.get();
        return new SessionStats((long) values[0], values[1], values[2], values[3], values[4]);
    }

    /** Increment the session's message count. */
    public static void incrementMessageCount(SqliteDatabase db, String sessionId) {
        int changes = db.run(
            "UPDATE session_stats SET message_count = message_count + 1 WHERE session_id = ?",
            sessionId);
        if (changes != 1) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Missing stats row for session " + sessionId);
        }
    }

    /** Accumulate a usage record into the session's stats. */
    public static void addUsageToStats(SqliteDatabase db, String sessionId, Usage usage) {
        int changes = db.run("""
            UPDATE session_stats
            SET cached_tokens = cached_tokens + ?,
                uncached_tokens = uncached_tokens + ?,
                total_tokens = total_tokens + ?,
                cost_total = cost_total + ?
            WHERE session_id = ?
            """, usage.cacheRead(), usage.input() + usage.cacheWrite(),
            usage.totalTokens(), usage.cost().total(), sessionId);
        if (changes != 1) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Missing stats row for session " + sessionId);
        }
    }

    /** Delete the stats row for the session. */
    public static void deleteStats(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM session_stats WHERE session_id = ?", sessionId);
    }
}
