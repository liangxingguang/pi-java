package com.pijava.session.sqlite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.session.BranchBounds;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.LanePointer;
import com.pijava.agent.session.LogItem;
import com.pijava.agent.session.LogOptions;
import com.pijava.agent.session.RecordQuery;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionJson;
import com.pijava.agent.session.SessionStats;
import com.pijava.agent.session.SessionStorage;
import com.pijava.session.sqlite.storage.BranchEntryRows;
import com.pijava.session.sqlite.storage.EntryRows;
import com.pijava.session.sqlite.storage.FactRows;
import com.pijava.session.sqlite.storage.LaneRows;
import com.pijava.session.sqlite.storage.RecordRows;
import com.pijava.session.sqlite.storage.SequenceRows;
import com.pijava.session.sqlite.storage.SessionRows;
import com.pijava.session.sqlite.storage.StatsRows;

/**
 * SQLite session storage (aligned with pi's {@code SqliteSessionStorage}).
 * Every write runs in a transaction that first renews the writer lease
 * (owner+fence+unexpired), then performs the mutation and advances the shared
 * sequence. A background heartbeat keeps the lease alive.
 */
public final class SqliteSessionStorage implements SessionStorage<SqliteSessionMetadata> {

    private final SqliteDatabase db;
    private final SqliteSessionMetadata metadata;
    private final WriterLease lease;
    private final long ttlMs;
    private final long heartbeatIntervalMs;
    private final Runnable onRelease;
    private final Object lock = new Object();
    private final ScheduledExecutorService heartbeat =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "pi-sqlite-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    private volatile SessionError leaseError;
    private volatile boolean closing;

    SqliteSessionStorage(SqliteDatabase db, SqliteSessionMetadata metadata, WriterLease lease,
                         long ttlMs, long heartbeatIntervalMs, Runnable onRelease) {
        this.db = db;
        this.metadata = metadata;
        this.lease = lease;
        this.ttlMs = ttlMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.onRelease = onRelease;
        scheduleHeartbeat();
    }

    boolean isForSession(String sessionId) {
        return metadata.id().equals(sessionId);
    }

    /** Release the lease and stop the heartbeat. */
    public void release() {
        closing = true;
        heartbeat.shutdownNow();
        try {
            synchronized (lock) {
                db.transaction(() -> WriterLease.release(db, metadata.id(), lease));
            }
        } finally {
            onRelease.run();
        }
    }

    // ── Reads ──────────────────────────────────────────────

    @Override
    public SqliteSessionMetadata getMetadata() {
        var row = SessionRows.readSessionRow(db, metadata.id())
            .orElseThrow(() -> new SessionError(SessionErrorCode.NOT_FOUND,
                "Session not found: " + metadata.id()));
        return SessionRows.decodeSessionMetadata(row, metadata.path());
    }
    @Override
    public List<LanePointer> getLanes() {
        return LaneRows.readLanes(db, metadata.id()).stream()
            .map(row -> new LanePointer(row.lane(), row.leafId()))
            .toList();
    }
    @Override
    public Entry getEntry(String id) {
        var row = EntryRows.readEntryRow(db, metadata.id(), id);
        return row.map(EntryRows::decodeEntry).orElse(null);
    }
    @Override
    public List<Entry> findEntries(EntryQuery query) {
        var q = query == null ? EntryQuery.all() : query;
        String sqlType = q.type() != null ? q.type()
            : (q.customType() != null ? "custom" : null);
        Integer sqlLimit = q.customType() == null ? q.limit() : null;
        var rows = EntryRows.readEntryRows(db, metadata.id(),
            EntryRows.QueryOptions.of(null, q.cursor(), sqlType, q.order(), sqlLimit));
        var entries = rows.stream().map(EntryRows::decodeEntry)
            .filter(e -> matchesEntryQuery(e, q)).toList();
        return q.limit() == null ? entries : entries.subList(0, Math.min(q.limit(), entries.size()));
    }

    @Override
    public List<Entry> findEntriesOnBranch(EntryQuery query, BranchBounds bounds, String start) {
        var q = query == null ? EntryQuery.all() : query;
        var b = bounds == null ? BranchBounds.none() : bounds;
        var cached = BranchEntryRows.readCachedBranch(db, metadata.id(), start);
        if (cached.isEmpty()) {
            if (EntryRows.readEntryRow(db, metadata.id(), start).isEmpty()) {
                throw new SessionError(SessionErrorCode.NOT_FOUND,
                    "Entry not found: " + start);
            }
            throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                "Branch cache missing entry " + start);
        }
        var rows = BranchEntryRows.queryCachedBranchRows(db, metadata.id(), cached.get(),
            new BranchEntryRows.Query(q.type(), q.customType(), b.stopAtType(),
                b.stopAtId(), q.cursor(), q.order(), q.limit()));
        validateCachedBranchRows(rows, q, b);
        var entries = rows.stream()
            .map(BranchEntryRows.CachedBranchEntryRow::id)
            .map(id -> EntryRows.readEntryRow(db, metadata.id(), id)
                .map(EntryRows::decodeEntry).orElse(null))
            .filter(java.util.Objects::nonNull)
            .filter(e -> matchesEntryQuery(e, q)).toList();
        return q.limit() == null ? entries : entries.subList(0, Math.min(q.limit(), entries.size()));
    }

    @Override
    public List<LaneRecord> findRecords(RecordQuery query) {
        return RecordRows.readRecordRows(db, metadata.id(),
            query == null ? RecordQuery.all() : query).stream()
            .map(RecordRows::decodeRecord).toList();
    }

    @Override
    public List<LaneRecord.OperationStarted> findOpenOperations(String lane, int limit) {
        Integer sqlLimit = limit == 0 ? null : limit;
        return RecordRows.readOpenOperationRows(db, metadata.id(), lane, sqlLimit).stream()
            .map(RecordRows::decodeRecord)
            .map(r -> {
                if (!(r instanceof LaneRecord.OperationStarted started)) {
                    throw new SessionError(SessionErrorCode.STORAGE,
                        "Expected operation_started record");
                }
                return started;
            }).toList();
    }

    @Override
    public List<LogItem> getLog(LogOptions options) {
        var o = options == null ? LogOptions.none() : options;
        Long afterSeq = o.afterSeq();
        Integer limit = o.limit();
        var entryRows = EntryRows.readEntryRows(db, metadata.id(),
            EntryRows.QueryOptions.of(afterSeq, null, null, EntryOrder.OLDEST_FIRST, limit));
        var recordRows = RecordRows.readRecordRows(db, metadata.id(),
            new RecordQuery(null, null, null, null, afterSeq, EntryOrder.OLDEST_FIRST, limit));
        var laneRows = LaneRows.readLaneMoveRows(db, metadata.id(), afterSeq, limit);
        var factRows = FactRows.readFactRows(db, metadata.id(), afterSeq, limit);

        var logRows = new java.util.ArrayList<LogItem>();
        entryRows.forEach(row -> logRows.add(new LogItem.EntryItem(row.seq(), EntryRows.decodeEntry(row))));
        recordRows.forEach(row -> logRows.add(new LogItem.RecordItem(row.seq(), RecordRows.decodeRecord(row))));
        laneRows.forEach(row -> logRows.add(new LogItem.LaneItem(row.seq(), row.lane(), row.leafId())));
        factRows.forEach(row -> logRows.add(factItem(row)));
        logRows.sort(java.util.Comparator.comparingLong(LogItem::seq));
        if (limit != null && logRows.size() > limit) {
            return List.copyOf(logRows.subList(0, limit));
        }
        return List.copyOf(logRows);
    }

    private static LogItem factItem(FactRows.FactRow row) {
        if ("name".equals(row.kind())) {
            return new LogItem.NameItem(row.seq(), parseFactValue(row.value()));
        }
        return new LogItem.LabelItem(row.seq(), row.key() == null ? "" : row.key(),
            parseFactValue(row.value()));
    }

    private static String parseFactValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            var node = SessionJson.mapper().readTree(value);
            return node != null && node.isTextual() ? node.textValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getName() {
        var row = FactRows.readLatestFact(db, metadata.id(), "name", null);
        return row.map(FactRows.FactRow::value).map(SqliteSessionStorage::parseFactValue).orElse(null);
    }

    @Override
    public String getLabel(String id) {
        var row = FactRows.readLatestFact(db, metadata.id(), "label", id);
        return row.map(FactRows.FactRow::value).map(SqliteSessionStorage::parseFactValue).orElse(null);
    }

    @Override
    public SessionStats getStats() {
        return StatsRows.readStats(db, metadata.id());
    }

    // ── Writes ─────────────────────────────────────────────

    @Override
    public void createLane(String lane, String at) {
        enqueueWriteAction(() -> {
            if (LaneRows.readLane(db, metadata.id(), lane).isPresent()) {
                throw new SessionError(SessionErrorCode.ALREADY_EXISTS,
                    "Lane already exists: " + lane);
            }
            if (at != null && EntryRows.readEntryRow(db, metadata.id(), at).isEmpty()) {
                throw new SessionError(SessionErrorCode.NOT_FOUND, "Entry not found: " + at);
            }
            long seq = SequenceRows.getNextSequence(db, metadata.id());
            LaneRows.createLane(db, metadata.id(), seq, lane, at);
            SequenceRows.advanceSequence(db, metadata.id(), seq);
        });
    }

    @Override
    public void moveLane(String lane, String to) {
        enqueueWriteAction(() -> {
            if (LaneRows.readLane(db, metadata.id(), lane).isEmpty()) {
                throw new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: " + lane);
            }
            if (to != null && EntryRows.readEntryRow(db, metadata.id(), to).isEmpty()) {
                throw new SessionError(SessionErrorCode.NOT_FOUND, "Entry not found: " + to);
            }
            long seq = SequenceRows.getNextSequence(db, metadata.id());
            LaneRows.moveLane(db, metadata.id(), seq, lane, to);
            SequenceRows.advanceSequence(db, metadata.id(), seq);
        });
    }

    @Override
    public <T extends Entry> T appendEntry(ProvisionedEntry<T> entry, String lane) {
        return enqueueWrite(() -> {
            String parentId = LaneRows.readLaneHead(db, metadata.id(), lane);
            assertUnusedId(entry.entry().id());
            long seq = SequenceRows.getNextSequence(db, metadata.id());
            // committed() preserves the runtime subtype of the provisioned entry.
            @SuppressWarnings("unchecked")
            T committed = (T) entry.entry().committed(seq, parentId, java.time.Instant.ofEpochMilli(System.currentTimeMillis()));
            EntryRows.insertEntryRow(db, metadata.id(), new EntryRows.NewEntryRow(
                seq, committed.id(), committed.parentId(), committed.type(),
                timestampToText(committed.timestamp()), EntryRows.entryPayload(committed)));
            LaneRows.setLaneLeaf(db, metadata.id(), lane, committed.id());
            BranchCache.appendEntryToBranchCache(db, metadata.id(), committed.id(), seq,
                committed.type(), committed instanceof Entry.Custom c ? c.customType() : null,
                committed.parentId());
            if (committed.type().equals("message")) {
                StatsRows.incrementMessageCount(db, metadata.id());
            }
            SequenceRows.advanceSequence(db, metadata.id(), seq);
            return committed;
        });
    }

    @Override
    public <T extends LaneRecord> T appendRecord(NewRecord<T> record) {
        return enqueueWrite(() -> {
            var provisioned = record.record();
            if (LaneRows.readLane(db, metadata.id(), provisioned.lane()).isEmpty()) {
                throw new SessionError(SessionErrorCode.INVALID_LANE,
                    "Lane not found: " + provisioned.lane());
            }
            assertUnusedId(provisioned.id());
            long seq = SequenceRows.getNextSequence(db, metadata.id());
            if (provisioned instanceof LaneRecord.OperationStarted) {
                LaneRows.startLaneOperation(db, metadata.id(), provisioned.lane(), provisioned.id());
            }
            // One timestamp source so the returned record equals the decoded row.
            Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
            RecordRows.appendRecordRow(db, metadata.id(), new RecordRows.NewRecordRow(
                seq, provisioned.id(), provisioned.lane(), recordRunId(provisioned),
                provisioned.type(), recordOpKind(provisioned),
                timestampToText(now), recordPayload(provisioned)));
            if (provisioned instanceof LaneRecord.OperationFinished finished) {
                LaneRows.finishLaneOperation(db, metadata.id(), provisioned.lane(), finished.runId());
            }
            if (provisioned instanceof LaneRecord.UsageRecord usage) {
                StatsRows.addUsageToStats(db, metadata.id(), usage.usage());
            }
            SequenceRows.advanceSequence(db, metadata.id(), seq);
            // committed() preserves the runtime subtype of the provisioned record.
            @SuppressWarnings("unchecked")
            T committed = (T) provisioned.committed(seq, now);
            return committed;
        });
    }

    @Override
    public void setName(String name) {
        enqueueWriteAction(() -> {
            long seq = SequenceRows.getNextSequence(db, metadata.id());
            FactRows.appendFact(db, metadata.id(), seq, "name", null,
                name == null ? null : jsonString(name));
            SequenceRows.advanceSequence(db, metadata.id(), seq);
        });
    }

    @Override
    public void setLabel(String id, String label) {
        enqueueWriteAction(() -> {
            if (EntryRows.readEntryRow(db, metadata.id(), id).isEmpty()) {
                throw new SessionError(SessionErrorCode.NOT_FOUND, "Entry not found: " + id);
            }
            long seq = SequenceRows.getNextSequence(db, metadata.id());
            FactRows.appendFact(db, metadata.id(), seq, "label", id,
                label == null ? null : jsonString(label));
            SequenceRows.advanceSequence(db, metadata.id(), seq);
        });
    }

    /** Wait for all queued writes (serialized lock drained on return). */
    @Override
    public void drain() {
        // No-op: the synchronized write path is the serial queue.
    }

    @Override
    public void close() {
        release();
    }

    // ── Internals ──────────────────────────────────────────

    private void enqueueWriteAction(Runnable operation) {
        enqueueWrite(() -> {
            operation.run();
            return null;
        });
    }

    private <T> T enqueueWrite(java.util.function.Supplier<T> operation) {
        synchronized (lock) {
            if (closing) {
                throw new SessionError(SessionErrorCode.STORAGE,
                    "SQLite session " + metadata.id() + " is closed");
            }
            if (leaseError != null) {
                throw leaseError;
            }
            return db.transaction(() -> {
                long now = System.currentTimeMillis();
                if (!WriterLease.renew(db, metadata.id(), lease, now, ttlMs)) {
                    leaseError = lostWriterError();
                    heartbeat.shutdownNow();
                    throw leaseError;
                }
                return operation.get();
            });
        }
    }

    private void scheduleHeartbeat() {
        if (closing) {
            return;
        }
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                if (closing || leaseError != null) {
                    return;
                }
                db.transaction(() -> {
                    long now = System.currentTimeMillis();
                    if (!WriterLease.renew(db, metadata.id(), lease, now, ttlMs)) {
                        leaseError = lostWriterError();
                    }
                });
            } catch (RuntimeException e) {
                // A transient heartbeat failure is retried; writes verify ownership.
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void assertUnusedId(String id) {
        if (EntryRows.idExistsInEntries(db, metadata.id(), id)
                || RecordRows.idExistsInRecords(db, metadata.id(), id)) {
            throw new SessionError(SessionErrorCode.ALREADY_EXISTS, "ID already exists: " + id);
        }
    }

    private static SessionError lostWriterError() {
        return new SessionError(SessionErrorCode.STORAGE,
            "SQLite session writer lease was lost");
    }

    private static String timestampToText(Instant timestamp) {
        return timestamp.toString();
    }

    private static String jsonString(String value) {
        try {
            return SessionJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode fact value", e);
        }
    }

    private static String recordPayload(LaneRecord record) {
        try {
            return SessionJson.mapper().writeValueAsString(record);
        } catch (Exception e) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "Durable payload " + e.getMessage(), e);
        }
    }

    private static String recordRunId(LaneRecord record) {
        if (record instanceof LaneRecord.OperationStarted started) {
            return started.id();
        }
        return switch (record) {
            case LaneRecord.OperationFinished r -> r.runId();
            case LaneRecord.StepAttempt r -> r.runId();
            case LaneRecord.ToolStarted r -> r.runId();
            case LaneRecord.QueueEnqueued r -> r.runId();
            case LaneRecord.QueueCancelled r -> r.runId();
            case LaneRecord.WriteDeferred r -> r.runId();
            case LaneRecord.UsageRecord r -> r.runId();
            default -> null;
        };
    }

    private static String recordOpKind(LaneRecord record) {
        if (record instanceof LaneRecord.OperationStarted started
                && started.intent() != null) {
            return switch (started.intent()) {
                case LaneRecord.OperationStarted.Run r -> "run";
                case LaneRecord.OperationStarted.Compaction c -> "compaction";
                case LaneRecord.OperationStarted.Navigation n -> "navigation";
            };
        }
        return null;
    }

    private static boolean matchesEntryQuery(Entry entry, EntryQuery query) {
        return (query.type() == null || query.type().equals(entry.type()))
            && (query.customType() == null
                || (entry.type().equals("custom")
                    && query.customType().equals(((Entry.Custom) entry).customType())))
            && (query.cursor() == null
                || (query.order() == EntryOrder.OLDEST_FIRST
                    ? entry.seq() > query.cursor().afterSeq()
                    : entry.seq() < query.cursor().afterSeq()));
    }

    private static void validateCachedBranchRows(List<BranchEntryRows.CachedBranchEntryRow> rows,
                                                 EntryQuery query, BranchBounds bounds) {
        if (rows.isEmpty() || query.type() != null || query.customType() != null) {
            return;
        }
        var path = new java.util.ArrayList<>(rows);
        path.sort(java.util.Comparator.comparingLong(BranchEntryRows.CachedBranchEntryRow::entrySeq));
        boolean shouldIncludeRoot = bounds.stopAtId() == null && bounds.stopAtType() == null
            && query.cursor() == null
            && (query.order() == EntryOrder.OLDEST_FIRST || query.limit() == null);
        if (shouldIncludeRoot && path.getFirst().parentId() != null) {
            throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                "Entry " + path.getFirst().parentId() + " not found");
        }
        for (int i = 1; i < path.size(); i++) {
            var previous = path.get(i - 1);
            var current = path.get(i);
            if (!java.util.Objects.equals(current.parentId(), previous.id())) {
                throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                    "Entry " + current.parentId() + " not found");
            }
        }
    }
}
