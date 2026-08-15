package com.pijava.agent.session.jsonl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.session.BranchBounds;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.LanePointer;
import com.pijava.agent.session.LogItem;
import com.pijava.agent.session.LogOptions;
import com.pijava.agent.session.RecordQuery;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.SessionMutation;
import com.pijava.agent.session.SessionState;
import com.pijava.agent.session.SessionStats;
import com.pijava.agent.session.SessionStorage;

/**
 * JSONL v4 session storage (aligned with pi {@code JsonlSessionStorage}).
 * Writes go through a serialized tail chain (Java: {@code synchronized} on a
 * write lock): append to file first, then apply to the in-memory
 * {@link SessionState}. Load performs torn-tail repair for crash recovery.
 */
public final class JsonlSessionStorage implements SessionStorage<JsonlSessionMetadata> {

    private final JsonlSessionRepoFileSystem fs;
    private final JsonlSessionMetadata metadata;
    private final SessionState state = new SessionState();
    private final Object writeLock = new Object();

    private JsonlSessionStorage(JsonlSessionRepoFileSystem fs, JsonlSessionMetadata metadata) {
        this.fs = fs;
        this.metadata = metadata;
    }

    /** Create a storage backed by a fresh file. */
    public static JsonlSessionStorage create(JsonlSessionRepoFileSystem fs, Path path,
                                             JsonlV4Header header) {
        fs.writeFile(path, JsonlCodec.encodeHeader(header));
        return new JsonlSessionStorage(fs, metadataFromHeader(header, path, fs.fileInfoMtimeMs(path)));
    }

    /** Load a storage from an existing file, repairing a torn tail. */
    public static JsonlSessionStorage load(JsonlSessionRepoFileSystem fs, Path path) {
        String content = fs.readTextFile(path);
        String[] physicalLines = content.split("\n", -1);
        if (physicalLines.length > 0 && physicalLines[physicalLines.length - 1].isEmpty()) {
            physicalLines = java.util.Arrays.copyOf(physicalLines, physicalLines.length - 1);
        }
        if (physicalLines.length == 0 || physicalLines[0].isEmpty()) {
            throw new JsonlSessionError(path, 1, "is missing a header");
        }
        var headerResult = JsonlCodec.parseHeader(physicalLines[0]);
        if (!headerResult.ok()) {
            throw new JsonlSessionError(path, 1, headerResult.error());
        }
        var storage = new JsonlSessionStorage(fs,
            metadataFromHeader(headerResult.value(), path, fs.fileInfoMtimeMs(path)));

        for (int index = 1; index < physicalLines.length; index++) {
            String line = physicalLines[index];
            var mutationResult = JsonlCodec.parseMutation(line);
            if (!mutationResult.ok()) {
                boolean isTornTail = index == physicalLines.length - 1
                    && "syntax".equals(mutationResult.error().kind());
                if (isTornTail) {
                    // Drop the unacknowledged partial append by atomically publishing the valid prefix.
                    StringBuilder prefix = new StringBuilder();
                    for (int i = 0; i < index; i++) {
                        prefix.append(physicalLines[i]).append("\n");
                    }
                    String tempPath = path + ".tmp";
                    fs.writeFile(Path.of(tempPath), prefix.toString());
                    fs.renameFile(Path.of(tempPath), path);
                    return storage;
                }
                throw new JsonlSessionError(path, index + 1, mutationResult.error());
            }
            try {
                storage.applyMutation(mutationResult.value());
            } catch (SessionError e) {
                if (e.code() == SessionErrorCode.INVALID_ENTRY) {
                    throw new JsonlSessionError(path, index + 1, e);
                }
                throw e;
            }
        }

        if (!content.endsWith("\n")) {
            fs.appendFile(path, "\n");
        }
        if (storage.metadata.sourceFormat() == 3) {
            storage.migrateV3ToV4(path);
        }
        return storage;
    }

    /** Atomically publish a forked copy at {@code path}. */
    public JsonlSessionStorage fork(Path path, JsonlV4Header header, ForkOptions options) {
        var mutations = state.createForkMutations(options);
        String tempPath = path + ".tmp";
        try {
            var target = create(fs, Path.of(tempPath), header);
            for (var mutation : mutations) {
                fs.appendFile(Path.of(tempPath), JsonlCodec.encodeMutation(mutation));
                target.applyMutation(mutation);
            }
            fs.renameFile(Path.of(tempPath), path);
        } catch (RuntimeException e) {
            fs.remove(Path.of(tempPath), true);
            throw e;
        }
        return load(fs, path);
    }

    /** Wait for all queued writes (serialized chain is drained on return). */
    @Override
    public void drain() {
        synchronized (writeLock) {
            // The synchronized block is the serial tail chain; nothing queued remains.
        }
    }

    @Override
    public JsonlSessionMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<LanePointer> getLanes() {
        return state.getLanes();
    }

    @Override
    public void createLane(String lane, String at) {
        synchronized (writeLock) {
            state.validateNewLane(lane);
            state.validateTarget(at);
            var mutation = new SessionMutation.Lane(state.nextSequence(), lane, at);
            appendMutation(mutation);
            applyMutation(mutation);
        }
    }

    @Override
    public void moveLane(String lane, String to) {
        synchronized (writeLock) {
            state.requireLane(lane);
            state.validateTarget(to);
            var mutation = new SessionMutation.Lane(state.nextSequence(), lane, to);
            appendMutation(mutation);
            applyMutation(mutation);
        }
    }

    @Override
    public <T extends Entry> T appendEntry(ProvisionedEntry<T> entry, String lane) {
        synchronized (writeLock) {
            String parentId = state.requireLane(lane);
            state.validateUnusedId(entry.entry().id());
            // The generic cast is safe: committed() preserves the runtime subtype of the provisioned entry.
            @SuppressWarnings("unchecked")
            T committed = (T) entry.entry().committed(state.nextSequence(), parentId, Instant.now());
            var mutation = new SessionMutation.Entry(lane, committed);
            appendMutation(mutation);
            applyMutation(mutation);
            return committed;
        }
    }

    @Override
    public <T extends LaneRecord> T appendRecord(NewRecord<T> record) {
        synchronized (writeLock) {
            state.requireLane(record.record().lane());
            state.validateUnusedId(record.record().id());
            var currentOpen = state.findOpenOperations(record.record().lane(), 1);
            if (record.record() instanceof LaneRecord.OperationStarted
                    && !currentOpen.isEmpty()) {
                throw new SessionError(SessionErrorCode.STORAGE,
                    "Lane " + record.record().lane()
                        + " already has an open operation " + currentOpen.getFirst().id());
            }
            // The generic cast is safe: committed() preserves the runtime subtype of the provisioned record.
            @SuppressWarnings("unchecked")
            T committed = (T) record.record().committed(state.nextSequence(), Instant.now());
            var mutation = new SessionMutation.Record(committed);
            appendMutation(mutation);
            applyMutation(mutation);
            return committed;
        }
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
        synchronized (writeLock) {
            var mutation = new SessionMutation.FactName(state.nextSequence(), name);
            appendMutation(mutation);
            applyMutation(mutation);
        }
    }

    @Override
    public String getLabel(String id) {
        return state.getLabel(id);
    }

    @Override
    public void setLabel(String id, String label) {
        synchronized (writeLock) {
            state.validateTarget(id);
            var mutation = new SessionMutation.FactLabel(state.nextSequence(), id, label);
            appendMutation(mutation);
            applyMutation(mutation);
        }
    }

    @Override
    public SessionStats getStats() {
        return state.getStats();
    }

    /** JSONL storage is stateless between opens; nothing to release. */
    @Override
    public void close() {
        // No-op for the JSONL backend.
    }

    // ── Internals ──────────────────────────────────────────

    private void appendMutation(SessionMutation mutation) {
        fs.appendFile(metadata.path(), JsonlCodec.encodeMutation(mutation));
    }

    private void applyMutation(SessionMutation mutation) {
        state.applyMutation(mutation);
    }

    private void migrateV3ToV4(Path path) {
        // Lazy migration: rewrite the header as v4, preserving the legacy
        // parent path marker; mutation lines are already v4-shaped.
        var header = new JsonlV4Header("header", 4, metadata.id(),
            metadata.createdAt().toEpochMilli(), metadata.cwd(),
            metadata.parentSessionId(), metadata.legacyParentSessionPath(),
            metadata.metadata());
        String tempPath = path + ".tmp";
        StringBuilder content = new StringBuilder(JsonlCodec.encodeHeader(header));
        for (var item : state.getLog(LogOptions.none())) {
            content.append(JsonlCodec.encodeMutation(mutationFromLogItem(item)));
        }
        fs.writeFile(Path.of(tempPath), content.toString());
        fs.renameFile(Path.of(tempPath), path);
    }

    static SessionMutation mutationFromLogItem(LogItem item) {
        return switch (item) {
            case LogItem.EntryItem e -> new SessionMutation.Entry(null, e.entry());
            case LogItem.RecordItem r -> new SessionMutation.Record(r.record());
            case LogItem.LaneItem l -> new SessionMutation.Lane(l.seq(), l.lane(), l.leafId());
            case LogItem.NameItem n -> new SessionMutation.FactName(n.seq(), n.name());
            case LogItem.LabelItem l -> new SessionMutation.FactLabel(l.seq(), l.targetId(), l.label());
        };
    }

    static JsonlSessionMetadata metadataFromHeader(JsonlV4Header header, Path path, long modifiedAtMs) {
        return new JsonlSessionMetadata(
            header.id(),
            Instant.ofEpochMilli(header.createdAtMs()),
            header.parentSessionId(),
            header.cwd(),
            path,
            modifiedAtMs,
            header.version() == 4 ? 4 : 3,
            header.legacyParentSessionPath(),
            header.metadata());
    }

    /** A JSONL file-level decode failure. */
    static final class JsonlSessionError extends RuntimeException {
        private final Path path;
        private final int line;

        JsonlSessionError(Path path, int line, JsonlCodec.DecodeError error) {
            super("Invalid session file " + path + ":" + line + ": " + error.getMessage(), error);
            this.path = path;
            this.line = line;
        }

        JsonlSessionError(Path path, int line, Throwable cause) {
            super("Invalid session file " + path + ":" + line + ": " + cause.getMessage(), cause);
            this.path = path;
            this.line = line;
        }

        JsonlSessionError(Path path, int line, String message) {
            super("Invalid session file " + path + ":" + line + ": " + message);
            this.path = path;
            this.line = line;
        }

        Path path() {
            return path;
        }

        int line() {
            return line;
        }
    }

}