package com.pijava.agent.session.memory;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.session.BranchBounds;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.LanePointer;
import com.pijava.agent.session.LogItem;
import com.pijava.agent.session.LogOptions;
import com.pijava.agent.session.RecordQuery;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionMutation;
import com.pijava.agent.session.SessionState;
import com.pijava.agent.session.SessionStats;
import com.pijava.agent.session.SessionStorage;

/**
 * In-memory session storage built on {@link SessionState}. Serves as the
 * conformance oracle and a test double for the JSONL/SQLite backends.
 */
public final class MemorySessionStorage implements SessionStorage<MemorySessionMetadata> {

    private final MemorySessionMetadata metadata;
    private final SessionState state = new SessionState();

    public MemorySessionStorage(MemorySessionMetadata metadata) {
        this.metadata = metadata;
    }

    /** Fork this storage into a new one with fresh metadata. */
    public MemorySessionStorage fork(MemorySessionMetadata newMetadata, ForkOptions options) {
        var storage = new MemorySessionStorage(newMetadata);
        for (var mutation : state.createForkMutations(options)) {
            storage.state.applyMutation(mutation);
        }
        return storage;
    }

    @Override
    public MemorySessionMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<LanePointer> getLanes() {
        return state.getLanes();
    }

    @Override
    public void createLane(String lane, String at) {
        state.validateNewLane(lane);
        state.validateTarget(at);
        state.applyMutation(new SessionMutation.Lane(state.nextSequence(), lane, at));
    }

    @Override
    public void moveLane(String lane, String to) {
        state.requireLane(lane);
        state.validateTarget(to);
        state.applyMutation(new SessionMutation.Lane(state.nextSequence(), lane, to));
    }

    @Override
    public <T extends Entry> T appendEntry(ProvisionedEntry<T> entry, String lane) {
        String parentId = state.requireLane(lane);
        state.validateUnusedId(entry.entry().id());
        // The generic cast is safe: committed() preserves the runtime subtype.
        @SuppressWarnings("unchecked")
        T committed = (T) entry.entry().committed(state.nextSequence(), parentId, Instant.now());
        state.applyMutation(new SessionMutation.Entry(lane, committed));
        return committed;
    }

    @Override
    public <T extends LaneRecord> T appendRecord(NewRecord<T> record) {
        state.requireLane(record.record().lane());
        state.validateUnusedId(record.record().id());
        var currentOpen = state.findOpenOperations(record.record().lane(), 1);
        if (record.record() instanceof LaneRecord.OperationStarted && !currentOpen.isEmpty()) {
            throw new SessionError(SessionErrorCode.STORAGE,
                "Lane " + record.record().lane()
                    + " already has an open operation " + currentOpen.getFirst().id());
        }
        // The generic cast is safe: committed() preserves the runtime subtype.
        @SuppressWarnings("unchecked")
        T committed = (T) record.record().committed(state.nextSequence(), Instant.now());
        state.applyMutation(new SessionMutation.Record(committed));
        return committed;
    }

    @Override
    public Entry getEntry(String id) {
        return state.getEntry(id);
    }

    @Override
    public List<Entry> findEntries(EntryQuery query) {
        return state.findEntries(query);
    }

    @Override
    public List<Entry> findEntriesOnBranch(EntryQuery query, BranchBounds bounds, String start) {
        return state.findEntriesOnBranch(query, bounds, start);
    }

    @Override
    public List<LaneRecord> findRecords(RecordQuery query) {
        return state.findRecords(query);
    }

    @Override
    public List<LaneRecord.OperationStarted> findOpenOperations(String lane, int limit) {
        return state.findOpenOperations(lane, limit);
    }

    @Override
    public List<LogItem> getLog(LogOptions options) {
        return state.getLog(options);
    }

    @Override
    public String getName() {
        return state.getName();
    }

    @Override
    public void setName(String name) {
        state.applyMutation(new SessionMutation.FactName(state.nextSequence(), name));
    }

    @Override
    public String getLabel(String id) {
        return state.getLabel(id);
    }

    @Override
    public void setLabel(String id, String label) {
        state.validateTarget(id);
        state.applyMutation(new SessionMutation.FactLabel(state.nextSequence(), id, label));
    }

    @Override
    public SessionStats getStats() {
        return state.getStats();
    }

    @Override
    public void drain() {
        // Nothing queued in memory.
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}