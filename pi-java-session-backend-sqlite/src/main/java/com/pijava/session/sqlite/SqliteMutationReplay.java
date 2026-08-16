package com.pijava.session.sqlite;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionMutation;
import com.pijava.session.sqlite.storage.EntryRows;
import com.pijava.session.sqlite.storage.FactRows;
import com.pijava.session.sqlite.storage.LaneRows;
import com.pijava.session.sqlite.storage.RecordRows;
import com.pijava.session.sqlite.storage.SequenceRows;
import com.pijava.session.sqlite.storage.StatsRows;

/**
 * Raw mutation replay for import (Phase 4 §4.7): inserts rows preserving the
 * mutation's original seq/parentId/timestamp rather than re-deriving them from
 * the lane head or clock.
 */
final class SqliteMutationReplay {

    private SqliteMutationReplay() {}

    static void replay(SqliteDatabase db, SqliteSessionMetadata metadata, SessionMutation mutation) {
        switch (mutation) {
            case SessionMutation.Entry e -> replayEntry(db, metadata, e);
            case SessionMutation.Record r -> replayRecord(db, metadata, r.record());
            case SessionMutation.Lane l -> {
                if (LaneRows.readLane(db, metadata.id(), l.lane()).isPresent()) {
                    LaneRows.moveLane(db, metadata.id(), l.seq(), l.lane(), l.leafId());
                } else {
                    LaneRows.createLane(db, metadata.id(), l.seq(), l.lane(), l.leafId());
                }
                advanceSequenceTo(db, metadata, l.seq());
            }
            case SessionMutation.FactName n -> {
                FactRows.appendFact(db, metadata.id(), n.seq(), "name", null,
                    n.name() == null ? null : SqliteCodecs.jsonString(n.name()));
                advanceSequenceTo(db, metadata, n.seq());
            }
            case SessionMutation.FactLabel l -> {
                FactRows.appendFact(db, metadata.id(), l.seq(), "label", l.targetId(),
                    l.label() == null ? null : SqliteCodecs.jsonString(l.label()));
                advanceSequenceTo(db, metadata, l.seq());
            }
        }
    }

    private static void replayEntry(SqliteDatabase db, SqliteSessionMetadata metadata,
                                    SessionMutation.Entry mutation) {
        var entry = mutation.entry();
        String lane = mutation.lane() == null ? "main" : mutation.lane();
        if (LaneRows.readLane(db, metadata.id(), lane).isEmpty()) {
            throw new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: " + lane);
        }
        EntryRows.insertEntryRow(db, metadata.id(), new EntryRows.NewEntryRow(
            entry.seq(), entry.id(), entry.parentId(), entry.type(),
            SqliteCodecs.timestampToText(entry.timestamp()), EntryRows.entryPayload(entry)));
        LaneRows.setLaneLeaf(db, metadata.id(), lane, entry.id());
        BranchCache.appendEntryToBranchCache(db, metadata.id(), entry.id(), entry.seq(),
            entry.type(), entry instanceof Entry.Custom c ? c.customType() : null,
            entry.parentId());
        if (entry.type().equals("message")) {
            StatsRows.incrementMessageCount(db, metadata.id());
        }
        advanceSequenceTo(db, metadata, entry.seq());
    }

    private static void replayRecord(SqliteDatabase db, SqliteSessionMetadata metadata,
                                     LaneRecord record) {
        if (LaneRows.readLane(db, metadata.id(), record.lane()).isEmpty()) {
            throw new SessionError(SessionErrorCode.INVALID_LANE,
                "Lane not found: " + record.lane());
        }
        if (record instanceof LaneRecord.OperationStarted) {
            LaneRows.startLaneOperation(db, metadata.id(), record.lane(), record.id());
        }
        RecordRows.appendRecordRow(db, metadata.id(), new RecordRows.NewRecordRow(
            record.seq(), record.id(), record.lane(), SqliteCodecs.recordRunId(record),
            record.type(), SqliteCodecs.recordOpKind(record),
            SqliteCodecs.timestampToText(record.timestamp()), SqliteCodecs.recordPayload(record)));
        if (record instanceof LaneRecord.OperationFinished finished) {
            LaneRows.finishLaneOperation(db, metadata.id(), record.lane(), finished.runId());
        }
        if (record instanceof LaneRecord.UsageRecord usage) {
            StatsRows.addUsageToStats(db, metadata.id(), usage.usage());
        }
        advanceSequenceTo(db, metadata, record.seq());
    }

    /** Advance next_seq past {@code seq} when it would otherwise fall behind. */
    private static void advanceSequenceTo(SqliteDatabase db, SqliteSessionMetadata metadata,
                                          long seq) {
        if (seq >= SequenceRows.getNextSequence(db, metadata.id())) {
            SequenceRows.setNextSequence(db, metadata.id(), seq + 1);
        }
    }
}
