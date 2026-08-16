package com.pijava.coding.agent.core;

import java.util.List;


import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionMetadata;
import com.pijava.coding.agent.cli.Args;

/**
 * Persistent-session lifecycle helpers for {@link AgentSession} (Phase 4
 * §13): write-through persistence of harness entries/records, resume seeding,
 * and {@code -c/-r/--fork} resolution against the repository.
 */
final class SessionPersistence {

    private SessionPersistence() {}

    /** Persist new transcript entries and lane records into the session. */
    static void persistPending(AgentSession owner, Session<?> persistent, String laneName) {
        var snapshot = owner.harness().snapshot(laneName);
        if (persistent.getLanes().stream().noneMatch(p -> laneName.equals(p.lane()))) {
            persistent.createLane(laneName, null);
        }
        for (var entry : snapshot.transcript()) {
            if (owner.persistedEntryIds().add(entry.id())) {
                persistent.appendEntry(new ProvisionedEntry<>(entry), laneName);
            }
        }
        for (var record : snapshot.records()) {
            if (owner.persistedRecordIds().add(record.id())) {
                persistent.appendRecord(new NewRecord<>(record));
            }
        }
    }

    /** Attach a persisted session: open, seed the harness transcript, set the name. */
    static void attach(AgentSession owner, PersistentSessionRepositories.RepositoryHandle handle,
                       SessionMetadata metadata) {
        Session<?> opened = handle.open(metadata);
        owner.session(opened);
        owner.name(sessionNameOf(opened, metadata));
        List<Entry> entries = opened.findEntries(
            new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null));
        owner.persistedEntryIds().clear();
        entries.forEach(e -> owner.persistedEntryIds().add(e.id()));
        owner.harness().seedTranscript(owner.laneName(), entries);
    }

    /** Resolve {@code -c/-r/--fork/--session-id} against the persistent repo. */
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

    static String sessionNameOf(Session<?> opened, SessionMetadata metadata) {
        String stored = opened.getName();
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return "session";
    }
}
