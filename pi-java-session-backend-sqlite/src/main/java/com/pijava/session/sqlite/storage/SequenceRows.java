package com.pijava.session.sqlite.storage;

import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.session.sqlite.SqliteDatabase;

/** session_sequences row operations (aligned with pi {@code session-sequences.ts}). */
public final class SequenceRows {

    private SequenceRows() {}

    /** Create the sequence row for a new session. */
    public static void createSequence(SqliteDatabase db, String sessionId, long nextSeq) {
        db.run("INSERT INTO session_sequences (session_id, next_seq) VALUES (?, ?)",
            sessionId, nextSeq);
    }

    /** Read the next sequence value for the session. */
    public static long getNextSequence(SqliteDatabase db, String sessionId) {
        var row = db.get("SELECT next_seq FROM session_sequences WHERE session_id = ?",
            rs -> rs.getLong("next_seq"), sessionId);
        if (row.isEmpty()) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Missing sequence row for session " + sessionId);
        }
        return row.get();
    }

    /** Set the next sequence value for the session. */
    public static void setNextSequence(SqliteDatabase db, String sessionId, long nextSeq) {
        db.run("UPDATE session_sequences SET next_seq = ? WHERE session_id = ?", nextSeq, sessionId);
    }

    /** Advance the session's next sequence to {@code seq + 1}. */
    public static void advanceSequence(SqliteDatabase db, String sessionId, long seq) {
        setNextSequence(db, sessionId, seq + 1);
    }

    /** Delete the sequence row for the session. */
    public static void deleteSequence(SqliteDatabase db, String sessionId) {
        db.run("DELETE FROM session_sequences WHERE session_id = ?", sessionId);
    }
}
