package com.pijava.agent.session;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.NewRecord;
import com.pijava.ai.message.Message;

/**
 * Session read/write facade (aligned with pi's {@code Session} class).
 * Wraps a {@link SessionStorage} and an {@link IdGenerator}, providing
 * branch views and convenience writes.
 *
 * @param <TMetadata> the backend-specific metadata type
 */
public final class Session<TMetadata extends SessionMetadata> implements SessionTree {

    private final SessionStorage<TMetadata> storage;
    private final IdGenerator idGenerator;

    public Session(SessionStorage<TMetadata> storage) {
        this(storage, UuidV7.INSTANCE);
    }

    public Session(SessionStorage<TMetadata> storage, IdGenerator idGenerator) {
        this.storage = storage;
        this.idGenerator = idGenerator;
    }

    /** Session metadata. */
    public TMetadata getMetadata() {
        return storage.getMetadata();
    }

    /** The underlying storage. */
    public SessionStorage<TMetadata> storage() {
        return storage;
    }

    /** The id generator. */
    public IdGenerator idGenerator() {
        return idGenerator;
    }

    /** Lane view; {@code main} returns this session itself. */
    public SessionTree view(String lane) {
        if ("main".equals(lane)) {
            return this;
        }
        return new LaneView(storage, lane, this);
    }

    /** All lanes. */
    public List<LanePointer> getLanes() {
        return storage.getLanes();
    }

    /** Create a lane at {@code at} (may be null). */
    public void createLane(String lane, String at) {
        storage.createLane(lane, at);
    }

    /** Move a lane to {@code to} (may be null). */
    public void moveLane(String lane, String to) {
        storage.moveLane(lane, to);
    }

    /** Append an entry and return the committed entry. */
    public <T extends Entry> T appendEntry(ProvisionedEntry<T> entry, String lane) {
        return storage.appendEntry(entry, lane);
    }

    /** Append a lane record and return the committed record. */
    public <T extends LaneRecord> T appendRecord(NewRecord<T> record) {
        return storage.appendRecord(record);
    }

    /** Query records. */
    public List<LaneRecord> findRecords(RecordQuery query) {
        if (query != null && query.operationKind() != null
                && !"operation_started".equals(query.type())) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY,
                "operationKind requires type \"operation_started\"");
        }
        return storage.findRecords(query);
    }

    /** Unfinished operation starts for a lane, newest first. */
    public List<LaneRecord.OperationStarted> findOpenOperations(String lane, int limit) {
        return storage.findOpenOperations(lane, limit);
    }

    /** The session log. */
    public List<LogItem> getLog(LogOptions options) {
        var o = options == null ? LogOptions.none() : options;
        if (o.limit() != null && o.limit() <= 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY, "limit must be a positive integer");
        }
        if (o.afterSeq() != null && o.afterSeq() < 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY,
                "cursor sequence must be a non-negative integer");
        }
        return storage.getLog(o);
    }

    // ── SessionTree ─────────────────────────────────────────

    @Override
    public String getLeafId() {
        var pointer = storage.getLanes().stream()
            .filter(p -> "main".equals(p.lane()))
            .findFirst()
            .orElseThrow(() -> new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: main"));
        return pointer.leafId();
    }

    @Override
    public Entry getEntry(String id) {
        return storage.getEntry(id);
    }

    @Override
    public SessionStats getStats() {
        return storage.getStats();
    }

    @Override
    public String getName() {
        return storage.getName();
    }

    @Override
    public void setName(String name) {
        storage.setName(name);
    }

    @Override
    public String getLabel(String targetId) {
        return storage.getLabel(targetId);
    }

    @Override
    public void setLabel(String targetId, String label) {
        storage.setLabel(targetId, label);
    }

    @Override
    public List<Entry> findEntries(EntryQuery query) {
        validateQuery(query);
        return storage.findEntries(query);
    }

    @Override
    public Entry findEntry(EntryQuery query) {
        validateQuery(query);
        var entries = storage.findEntries(withLimit(query, 1));
        return entries.isEmpty() ? null : entries.get(0);
    }

    @Override
    public List<Entry> findEntriesOnBranch(EntryQuery query, BranchBounds bounds) {
        return queryBranch("main", query, bounds, 0);
    }

    @Override
    public Entry findEntryOnBranch(EntryQuery query, BranchBounds bounds) {
        var entries = queryBranch("main", query, bounds, 1);
        return entries.isEmpty() ? null : entries.get(0);
    }

    @Override
    public String appendMessage(Message message) {
        return appendMessageToLane("main", message);
    }

    @Override
    public String appendCustomEntry(String customType, Map<String, Object> data) {
        return appendCustomEntryToLane("main", customType, data);
    }

    /** Release resources: JSONL no-op; SQLite releases lease + stops heartbeat. */
    public void close() {
        storage.close();
    }

    private static void validateQuery(EntryQuery query) {
        var q = query == null ? EntryQuery.all() : query;
        if (q.limit() != null && q.limit() <= 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY, "limit must be a positive integer");
        }
        if (q.cursor() != null && q.cursor().afterSeq() < 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY,
                "cursor sequence must be a non-negative integer");
        }
    }

    private List<Entry> queryBranch(String lane, EntryQuery query, BranchBounds bounds, int resultLimit) {
        var effectiveBounds = bounds;
        if (effectiveBounds == null) {
            effectiveBounds = BranchBounds.none();
        }
        String start = effectiveBounds.start();
        if (start == null) {
            var leaf = getLeafIdForLane(lane);
            if (leaf == null) {
                return List.of();
            }
            start = leaf;
        }
        var effectiveQuery = resultLimit > 0 ? withLimit(query, resultLimit) : query;
        return storage.findEntriesOnBranch(effectiveQuery, effectiveBounds, start);
    }

    private String getLeafIdForLane(String lane) {
        return storage.getLanes().stream()
            .filter(p -> lane.equals(p.lane()))
            .map(LanePointer::leafId)
            .findFirst()
            .orElseThrow(() -> new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: " + lane));
    }

    private String appendMessageToLane(String lane, Message message) {
        var provisioned = new ProvisionedEntry<Entry.Message>(
            new Entry.Message(idGenerator.next(), 0, null, null, message, null));
        return appendEntry(provisioned, lane).id();
    }

    private String appendCustomEntryToLane(String lane, String customType, Map<String, Object> data) {
        var provisioned = new ProvisionedEntry<Entry.Custom>(
            new Entry.Custom(idGenerator.next(), 0, null, null, customType, data));
        return appendEntry(provisioned, lane).id();
    }

    private static EntryQuery withLimit(EntryQuery query, int limit) {
        if (query == null) {
            return new EntryQuery(null, null, EntryOrder.NEWEST_FIRST, limit, null);
        }
        return new EntryQuery(query.type(), query.customType(), query.order(), limit, query.cursor());
    }
}
