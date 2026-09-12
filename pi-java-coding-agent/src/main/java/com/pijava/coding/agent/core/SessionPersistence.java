package com.pijava.coding.agent.core;

import java.util.List;


import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.RecordQuery;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionMetadata;
import com.pijava.coding.agent.cli.Args;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent-session lifecycle helpers for {@link AgentSession} (Phase 4
 * §13): write-through persistence of harness entries/records, resume seeding,
 * and {@code -c/-r/--fork} resolution against the repository.
 */
final class SessionPersistence {

    private static final Logger LOG = LoggerFactory.getLogger(SessionPersistence.class);

    private SessionPersistence() {}

    /** Persist new transcript entries and lane records into the session. */
    static void persistPending(AgentSession owner, Session<?> persistent, String laneName) {
        var snapshot = owner.harness().snapshot(laneName);
        int appended = 0;
        int records = 0;
        if (persistent.getLanes().stream().noneMatch(p -> laneName.equals(p.lane()))) {
            persistent.createLane(laneName, null);
        }
        for (var entry : snapshot.transcript()) {
            if (owner.persistedEntryIds().add(entry.id())) {
                persistent.appendEntry(new ProvisionedEntry<>(entry), laneName);
                appended++;
            }
        }
        for (var record : snapshot.records()) {
            if (owner.persistedRecordIds().add(record.id())) {
                persistent.appendRecord(new NewRecord<>(record));
                records++;
            }
        }
        // 每步 action 后都会被调用（docs/27 §2.1 的逐条落盘），只在确有写入时记
        // INFO —— 否则一个长 run 会为每次空 flush 刷一行日志。
        if (appended > 0 || records > 0) {
            LOG.info("[session] persistPending: transcript={} appended={} records={} persistedIds={}",
                snapshot.transcript().size(), appended, records, owner.persistedEntryIds().size());
        }
    }

    /**
     * Attach a persisted session: open, rebuild the lane's orchestration state
     * from its record log, then seed the compaction-aware harness transcript.
     *
     * <p>The two are deliberately separate (docs/21 D9): the record fold owns
     * orchestration state (phase / run id / queues / newest own entry), while
     * the transcript seed owns the display context.</p>
     */
    static void attach(AgentSession owner, PersistentSessionRepositories.RepositoryHandle handle,
                       SessionMetadata metadata) {
        Session<?> opened = handle.open(metadata);
        owner.session(opened);
        owner.name(sessionNameOf(opened, metadata));
        String lane = owner.laneName();
        List<Entry> entries = opened.findEntries(
            new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null));
        owner.persistedEntryIds().clear();
        entries.forEach(e -> owner.persistedEntryIds().add(e.id()));
        restoreFromRecordLog(owner, opened, lane, entries);
        // Seed the lane with the compaction-aware context (pi buildSessionContext),
        // not all entries — otherwise resume would re-inflate the compacted prefix.
        // Display paths (accumulatedEntries) still read full history from storage.
        String leafId = entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
        owner.harness().seedTranscript(owner.laneName(),
            ContextEntries.contextEntries(ContextEntries.pathToLeaf(entries, leafId)));
    }

    /**
     * Rebuild the lane's orchestration state from its record log (docs/21 D9).
     *
     * <p>The record slice is lane-scoped, and the entry slices are anchored to
     * the lane's last {@code OperationStarted}: a resumed lane only needs the
     * entries that operation appended, not the whole session. Configuration
     * entries are cumulative, so they are selected by type across all entries.
     * </p>
     *
     * <p>Recovery is refused, not guessed, when the log is inconsistent — the
     * fold throws {@code RecordLogCorruption}.</p>
     */
    private static void restoreFromRecordLog(AgentSession owner, Session<?> opened, String lane,
                                             List<Entry> entries) {
        List<LaneRecord> records = opened.findRecords(new RecordQuery(
            lane, null, null, null, null, EntryOrder.OLDEST_FIRST, null));
        if (records.isEmpty()) {
            return;
        }
        long anchor = Long.MIN_VALUE;
        for (var record : records) {
            if (record instanceof LaneRecord.OperationStarted started) {
                anchor = Math.max(anchor, started.seq());
            }
        }
        final long anchorSeq = anchor;
        List<Entry> ownEntries = anchorSeq == Long.MIN_VALUE ? entries
            : entries.stream().filter(e -> e.seq() > anchorSeq).toList();
        List<Entry> configurationEntries = entries.stream()
            .filter(Entry::isConfiguration).toList();
        owner.harness().restoreFromRecords(lane, records, ownEntries, configurationEntries);
        // The lane now carries its persisted records; mark them as written so
        // write-through does not append them a second time.
        owner.persistedRecordIds().clear();
        records.forEach(r -> owner.persistedRecordIds().add(r.id()));
    }

    /** Resolve {@code -c/-r/--fork/--session-id} against the persistent repo. */
    /** Web 默认：恢复最近一个持久会话（刷新/重连保留历史），仅当无会话时新建。 */
    static AgentSession resolvePersistentWeb(AgentSession session, Args args) {
        var handle = session.persistentRepository();
        var cwd = System.getProperty("user.dir");
        var latest = handle.latest();
        return latest.map(meta -> {
            attach(session, handle, meta);
            return session;
        }).orElseGet(() -> {
            var created = handle.create(cwd, null);
            session.session(created);
            attach(session, handle, created.getMetadata());
            return session;
        });
    }

    static AgentSession resolvePersistent(AgentSession session, Args args) {
        var handle = session.persistentRepository();
        var cwd = System.getProperty("user.dir");
        if (args.continue_()) {
            return handle.latest().map(meta -> {
                attach(session, handle, meta);
                return session;
            }).orElseThrow(() -> new IllegalStateException("No previous session to continue"));
        }
        if (args.sessionId() != null) {
            return handle.find(args.sessionId()).map(meta -> {
                attach(session, handle, meta);
                return session;
            }).orElseGet(() -> {
                var created = handle.create(cwd, null);
                session.session(created);
                attach(session, handle, created.getMetadata());
                return session;
            });
        }
        if (args.resume() || args.session() != null) {
            return handle.find(args.session()).map(meta -> {
                attach(session, handle, meta);
                return session;
            }).orElseThrow(() -> new IllegalStateException("Session not found: " + args.session()));
        }
        if (args.fork() != null) {
            return handle.find(args.fork()).map(meta -> {
                var forked = handle.fork(meta, new ForkOptions.Tree(), cwd);
                session.session(forked);
                session.name(session.sessionName() + " (fork)");
                attach(session, handle, forked.getMetadata());
                return session;
            }).orElseThrow(() -> new IllegalStateException("Session not found: " + args.fork()));
        }
        var created = handle.create(cwd, null);
        session.session(created);
        return session;
    }

    /** Resolve {@code -c/-r/--fork/--session-id} against the in-memory registry (tests). */
    static AgentSession resolveInMemory(AgentSession session, com.pijava.coding.agent.cli.Args args) {
        var repository = session.inMemoryRepository();
        if (args.continue_()) {
            return repository.latest().orElseThrow(() -> new IllegalStateException(
                "No previous session to continue"));
        }
        if (args.sessionId() != null) {
            return repository.find(args.sessionId()).orElseGet(() ->
                repository.createWithId(session, args.sessionId()));
        }
        if (args.resume() || args.session() != null) {
            return repository.find(args.session()).orElseThrow(() -> new IllegalStateException(
                "Session not found: " + args.session()));
        }
        if (args.fork() != null) {
            return repository.find(args.fork())
                .map(source -> repository.fork(source, session.sessionName()))
                .orElseThrow(() -> new IllegalStateException("Session not found: " + args.fork()));
        }
        repository.create(session);
        return session;
    }

    /** Export the current session to a JSONL file ({@code /export}). */
    static void exportJsonl(AgentSession owner, java.nio.file.Path target) {
        if (owner.persistentRepository() == null || owner.session() == null) {
            throw new IllegalStateException("No persistent session to export");
        }
        owner.persistentRepository().exportJsonl(owner.session(), target);
    }

    /** Import a JSONL file into a new persistent session ({@code /import}). */
    static AgentSession importJsonl(AgentSession owner, java.nio.file.Path source) {
        if (owner.persistentRepository() == null) {
            throw new IllegalStateException("No persistent repository to import into");
        }
        var imported = owner.persistentRepository().importJsonl(
            source, System.getProperty("user.dir"));
        attach(owner, owner.persistentRepository(), imported.getMetadata());
        return owner;
    }

    static String sessionNameOf(Session<?> opened, SessionMetadata metadata) {
        String stored = opened.getName();
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return "session";
    }
}
