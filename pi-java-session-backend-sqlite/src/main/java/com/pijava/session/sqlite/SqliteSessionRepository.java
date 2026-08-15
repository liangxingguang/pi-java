package com.pijava.session.sqlite;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.UuidV7;
import com.pijava.session.sqlite.storage.BranchEntryRows;
import com.pijava.session.sqlite.storage.BranchTipRows;
import com.pijava.session.sqlite.storage.EntryRows;
import com.pijava.session.sqlite.storage.FactRows;
import com.pijava.session.sqlite.storage.LaneRows;
import com.pijava.session.sqlite.storage.RecordRows;
import com.pijava.session.sqlite.storage.SequenceRows;
import com.pijava.session.sqlite.storage.SessionRows;
import com.pijava.session.sqlite.storage.StatsRows;
import com.pijava.session.sqlite.storage.WriterLeaseRows;

/**
 * SQLite session repository (aligned with pi {@code repo.ts}). Repository
 * operations are serialized by a lock; per-session writer leases are claimed
 * on create/open and released on close.
 */
public final class SqliteSessionRepository implements
    SessionRepository<SqliteSessionMetadata, SqliteSessionCreateOptions, SqliteSessionListOptions>,
    AutoCloseable {

    /** Default lease TTL. */
    public static final long DEFAULT_TTL_MS = 30_000;

    /** Default heartbeat cadence. */
    public static final long DEFAULT_HEARTBEAT_MS = 10_000;

    private final Path databasePath;
    private final long ttlMs;
    private final long heartbeatIntervalMs;
    private final ReentrantLock operations = new ReentrantLock();
    private final Set<SqliteSessionStorage> activeStorages = new HashSet<>();
    private SqliteDatabase database;

    public SqliteSessionRepository(Path databasePath) {
        this(databasePath, DEFAULT_TTL_MS, DEFAULT_HEARTBEAT_MS);
    }

    public SqliteSessionRepository(Path databasePath, long ttlMs, long heartbeatIntervalMs) {
        if (ttlMs <= 0 || heartbeatIntervalMs <= 0 || heartbeatIntervalMs >= ttlMs) {
            throw new IllegalArgumentException(
                "writerLease.heartbeatIntervalMs must be positive and less than ttlMs");
        }
        this.databasePath = databasePath;
        this.ttlMs = ttlMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    /** Open a repository, creating the database and applying migrations. */
    public static SqliteSessionRepository open(Path databasePath) {
        var repository = new SqliteSessionRepository(databasePath);
        repository.database();
        return repository;
    }

    @Override
    public Session<SqliteSessionMetadata> create(SqliteSessionCreateOptions options) {
        return serialized(() -> {
            var db = database();
            String id = options.id() != null ? options.id() : UuidV7.INSTANCE.next();
            if (SessionRows.sessionExists(db, id)) {
                throw new SessionError(SessionErrorCode.ALREADY_EXISTS,
                    "Session already exists: " + id);
            }
            String createdAt = Instant.now().toString();
            WriterLease lease = db.transaction(() -> {
                SessionRows.insertSessionRow(db, new SessionRows.NewSessionRow(
                    id, createdAt, options.cwd(), options.parentSessionId(),
                    serializeMetadata(options.metadata())));
                SequenceRows.createSequence(db, id, 1);
                StatsRows.createStats(db, id, 0);
                LaneRows.createInitialLane(db, id, "main", null);
                return claimWriterLease(db, id);
            });
            var row = SessionRows.readSessionRow(db, id)
                .orElseThrow(() -> new SessionError(SessionErrorCode.NOT_FOUND,
                    "Session not found: " + id));
            return sessionFromLease(db, SessionRows.decodeSessionMetadata(row, databasePath), lease);
        });
    }

    @Override
    public Session<SqliteSessionMetadata> open(SqliteSessionMetadata metadata) {
        return serialized(() -> claimSession(database(), metadata));
    }

    @Override
    public List<SqliteSessionMetadata> list(SqliteSessionListOptions options) {
        return serialized(() -> {
            if (!java.nio.file.Files.exists(databasePath)) {
                return List.of();
            }
            return SessionRows.readSessionRows(database(), options.cwd()).stream()
                .map(row -> SessionRows.decodeSessionMetadata(row, databasePath))
                .toList();
        });
    }

    @Override
    public void delete(SqliteSessionMetadata metadata) {
        serialized(() -> {
            releaseStoragesForSession(metadata.id());
            var db = database();
            db.transaction(() -> {
                if (!SessionRows.sessionExists(db, metadata.id())) {
                    WriterLeaseRows.delete(db, metadata.id());
                    return;
                }
                claimWriterLease(db, metadata.id());
                BranchCache.deleteBranchCache(db, metadata.id());
                FactRows.deleteFactRows(db, metadata.id());
                LaneRows.deleteLaneRows(db, metadata.id());
                RecordRows.deleteRecordRows(db, metadata.id());
                EntryRows.deleteEntryRows(db, metadata.id());
                WriterLeaseRows.delete(db, metadata.id());
                StatsRows.deleteStats(db, metadata.id());
                SequenceRows.deleteSequence(db, metadata.id());
                SessionRows.deleteSessionRow(db, metadata.id());
            });
            return null;
        });
    }

    @Override
    public Session<SqliteSessionMetadata> fork(SqliteSessionMetadata source, ForkOptions options,
                                               SqliteSessionCreateOptions createOptions) {
        return serialized(() -> {
            var db = database();
            var sourceRow = SessionRows.readSessionRow(db, source.id())
                .orElseThrow(() -> new SessionError(SessionErrorCode.NOT_FOUND,
                    "Session not found: " + source.id()));
            var sourceMetadata = SessionRows.decodeSessionMetadata(sourceRow, databasePath);
            String id = createOptions.id() != null ? createOptions.id() : UuidV7.INSTANCE.next();
            if (SessionRows.sessionExists(db, id)) {
                throw new SessionError(SessionErrorCode.ALREADY_EXISTS,
                    "Session already exists: " + id);
            }

            var entries = new java.util.ArrayList<EntryRows.EntryRow>();
            var lanes = new java.util.ArrayList<LaneRows.LaneRow>();
            var branchTips = new java.util.ArrayList<String>();
            String branchForkTargetId = null;

            if (options instanceof ForkOptions.Tree) {
                entries.addAll(EntryRows.readEntryRows(db, source.id(),
                    EntryRows.QueryOptions.of(null, null, null,
                        com.pijava.agent.session.EntryOrder.OLDEST_FIRST, null)));
                lanes.addAll(LaneRows.readLanes(db, source.id()));
                branchTips.addAll(BranchTipRows.readBranchTipIds(db, source.id()));
            } else {
                ForkOptions.Branch branch = (ForkOptions.Branch) options;
                var main = LaneRows.readLane(db, source.id(), "main")
                    .orElseThrow(() -> new SessionError(SessionErrorCode.INVALID_LANE,
                        "Lane not found: main"));
                String selectedEntryId = branch.entryId() != null ? branch.entryId() : main.leafId();
                if (selectedEntryId != null) {
                    var target = EntryRows.readEntryRow(db, source.id(), selectedEntryId)
                        .orElseThrow(() -> new SessionError(SessionErrorCode.INVALID_FORK_TARGET,
                            "Fork target is not a message entry: " + selectedEntryId));
                    if (!"message".equals(target.type())) {
                        throw new SessionError(SessionErrorCode.INVALID_FORK_TARGET,
                            "Fork target is not a message entry: " + selectedEntryId);
                    }
                    boolean at = branch.position() instanceof ForkOptions.Branch.At
                        || (branch.position() == null && branch.entryId() == null);
                    branchForkTargetId = at ? target.id() : target.parentId();
                }
                lanes.add(new LaneRows.LaneRow("", "main", branchForkTargetId, null));
            }

            final String forkTarget = branchForkTargetId;
            if (forkTarget != null) {
                var cached = BranchEntryRows.readCachedBranch(db, source.id(), forkTarget)
                    .orElseThrow(() -> new SessionError(SessionErrorCode.INVALID_FORK_TARGET,
                        "Fork target is not on a cached branch: " + forkTarget));
                var rows = BranchEntryRows.queryCachedBranchRows(db, source.id(), cached,
                    new BranchEntryRows.Query(null, null, null, null, null,
                        com.pijava.agent.session.EntryOrder.OLDEST_FIRST, null));
                for (var row : rows) {
                    var entryRow = EntryRows.readEntryRow(db, source.id(), row.id()).orElse(null);
                    if (entryRow != null) {
                        entries.add(entryRow);
                    }
                }
                branchTips.add(forkTarget);
            }

            var copiedIds = new java.util.HashSet<String>();
            entries.forEach(e -> copiedIds.add(e.id()));
            var latestName = FactRows.readLatestFact(db, source.id(), "name", null);
            var latestLabels = FactRows.readLatestLabelFacts(db, source.id());
            var labelsToCopy = latestLabels.stream()
                .filter(label -> options instanceof ForkOptions.Tree || copiedIds.contains(label[0]))
                .toList();
            String createdAt = Instant.now().toString();
            var metadata = createOptions.metadata() != null
                ? createOptions.metadata() : sourceMetadata.metadata();
            String parentSessionId = createOptions.parentSessionId() != null
                ? createOptions.parentSessionId() : source.id();
            var metadataJson = serializeMetadata(metadata);

            WriterLease lease;
            try {
                lease = db.transaction(() -> {
                    SessionRows.insertSessionRow(db, new SessionRows.NewSessionRow(
                        id, createdAt, createOptions.cwd(), parentSessionId, metadataJson));
                    SequenceRows.createSequence(db, id, 1);
                    long messageCount = entries.stream().filter(e -> "message".equals(e.type())).count();
                    StatsRows.createStats(db, id, messageCount);

                    long nextSeq = 1;
                    for (var entry : entries) {
                        EntryRows.insertEntryRow(db, id, new EntryRows.NewEntryRow(
                            nextSeq++, entry.id(), entry.parentId(), entry.type(),
                            entry.timestamp(), entry.payload()));
                    }

                    if (options instanceof ForkOptions.Tree) {
                        for (var lane : lanes) {
                            LaneRows.createLane(db, id, nextSeq++, lane.lane(), lane.leafId());
                        }
                    } else {
                        LaneRows.createInitialLane(db, id, "main", forkTarget);
                    }

                    if (latestName.isPresent() && latestName.get().value() != null) {
                        FactRows.appendFact(db, id, nextSeq++, "name", null,
                            latestName.get().value());
                    }
                    for (var label : labelsToCopy) {
                        FactRows.appendFact(db, id, nextSeq++, "label", label[0], label[1]);
                    }

                    SequenceRows.setNextSequence(db, id, nextSeq);
                    for (var tip : branchTips) {
                        BranchCache.buildCachedBranch(db, id, tip);
                    }
                    return claimWriterLease(db, id);
                });
            } catch (SessionError e) {
                throw e;
            } catch (RuntimeException e) {
                throw new SessionError(SessionErrorCode.STORAGE,
                    "Failed to fork SQLite session " + id, e);
            }
            var row = SessionRows.readSessionRow(db, id)
                .orElseThrow(() -> new SessionError(SessionErrorCode.NOT_FOUND,
                    "Session not found: " + id));
            return sessionFromLease(db, SessionRows.decodeSessionMetadata(row, databasePath), lease);
        });
    }

    /** Rebuild a session's branch cache from canonical parent links. */
    public void repairBranchCache(SqliteSessionMetadata metadata) {
        serialized(() -> {
            releaseStoragesForSession(metadata.id());
            var db = database();
            db.transaction(() -> {
                var lease = claimWriterLease(db, metadata.id());
                if (SessionRows.readSessionRow(db, metadata.id()).isEmpty()) {
                    throw new SessionError(SessionErrorCode.NOT_FOUND,
                        "Session not found: " + metadata.id());
                }
                BranchCache.rebuildBranchCache(db, metadata.id());
                WriterLease.release(db, metadata.id(), lease);
            });
            return null;
        });
    }

    @Override
    public void close() {
        serialized(() -> {
            for (var storage : List.copyOf(activeStorages)) {
                storage.release();
            }
            activeStorages.clear();
            if (database != null) {
                database.close();
                database = null;
            }
            return null;
        });
    }

    // ── Internals ──────────────────────────────────────────

    private SqliteDatabase database() {
        if (database == null) {
            var db = SqliteDatabase.open(databasePath);
            try {
                db.exec("PRAGMA journal_mode=WAL");
                db.exec("PRAGMA synchronous=FULL");
                db.exec("PRAGMA busy_timeout=5000");
                Migrations.applyMigrations(db);
                database = db;
            } catch (RuntimeException e) {
                db.close();
                throw e;
            }
        }
        return database;
    }

    private WriterLease claimWriterLease(SqliteDatabase db, String sessionId) {
        long now = System.currentTimeMillis();
        return WriterLease.acquire(db, sessionId, UuidV7.INSTANCE.next(), now, ttlMs)
            .orElseThrow(() -> new SessionError(SessionErrorCode.STORAGE,
                "SQLite session " + sessionId + " already has an active writer"));
    }

    private Session<SqliteSessionMetadata> sessionFromLease(SqliteDatabase db,
                                                            SqliteSessionMetadata metadata,
                                                            WriterLease lease) {
        SqliteSessionStorage[] holder = new SqliteSessionStorage[1];
        var storage = new SqliteSessionStorage(db, metadata, lease, ttlMs, heartbeatIntervalMs,
            () -> activeStorages.remove(holder[0]));
        holder[0] = storage;
        activeStorages.add(storage);
        return new Session<>(storage);
    }

    private Session<SqliteSessionMetadata> claimSession(SqliteDatabase db,
                                                        SqliteSessionMetadata metadata) {
        var active = activeStorages.stream()
            .filter(storage -> storage.isForSession(metadata.id()))
            .findFirst();
        if (active.isPresent()) {
            LaneRows.readLanes(db, metadata.id());
            return new Session<>(active.get());
        }
        var row = SessionRows.readSessionRow(db, metadata.id())
            .orElseThrow(() -> new SessionError(SessionErrorCode.NOT_FOUND,
                "Session not found: " + metadata.id()));
        var lease = db.transaction(() -> {
            var claimed = claimWriterLease(db, metadata.id());
            LaneRows.readLanes(db, metadata.id());
            return claimed;
        });
        return sessionFromLease(db, SessionRows.decodeSessionMetadata(row, databasePath), lease);
    }

    private void releaseStoragesForSession(String sessionId) {
        for (var storage : List.copyOf(activeStorages)) {
            if (storage.isForSession(sessionId)) {
                storage.release();
            }
        }
    }

    private <T> T serialized(java.util.function.Supplier<T> operation) {
        operations.lock();
        try {
            return operation.get();
        } finally {
            operations.unlock();
        }
    }

    private static String serializeMetadata(java.util.Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return com.pijava.agent.session.SessionJson.mapper().writeValueAsString(metadata);
        } catch (Exception e) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "SQLite session metadata must be an object", e);
        }
    }
}