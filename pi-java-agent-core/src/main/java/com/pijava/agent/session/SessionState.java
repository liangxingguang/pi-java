package com.pijava.agent.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;

/**
 * Canonical in-memory session state (aligned with pi's {@code state.ts}).
 *
 * <p>Maintains the shared sequence, used ids, entries (by id), records,
 * open operations per lane, lanes (main → null), log, stats, name and labels.
 * {@link #applyMutation} validates that seq is strictly consecutive, ids are
 * unique, parents exist and entries chain to their lane's leaf. JSONL and
 * Memory backends share this engine; SQLite mirrors its semantics via tables.</p>
 */
public final class SessionState {

    private long sequence;
    private final Set<String> usedIds = new LinkedHashSet<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> entriesById = new LinkedHashMap<>();
    private final List<LaneRecord> records = new ArrayList<>();
    private final Map<String, Map<String, LaneRecord.OperationStarted>> openOperationsByLane =
        new LinkedHashMap<>();
    private final Map<String, String> lanes = new LinkedHashMap<>();

    {
        lanes.put("main", null);
    }
    private final List<LogItem> log = new ArrayList<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private String name;
    private long messageCount;
    private double cachedTokens;
    private double uncachedTokens;
    private double totalTokens;
    private double costTotal;

    /** The next sequence number to assign. */
    public long nextSequence() {
        return sequence + 1;
    }

    /** All lanes with their current leaves. */
    public List<LanePointer> getLanes() {
        return lanes.entrySet().stream()
            .map(e -> new LanePointer(e.getKey(), e.getValue()))
            .toList();
    }

    /** The lane's leaf, throwing {@code invalid_lane} when the lane is missing. */
    public String requireLane(String lane) {
        String leafId = lanes.get(lane);
        if (leafId == null && !lanes.containsKey(lane)) {
            throw new SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: " + lane);
        }
        return leafId;
    }

    /** Throw {@code already_exists} when the lane already exists. */
    public void validateNewLane(String lane) {
        if (lanes.containsKey(lane)) {
            throw new SessionError(SessionErrorCode.ALREADY_EXISTS, "Lane already exists: " + lane);
        }
    }

    /** Throw {@code not_found} when the target id is non-null and missing. */
    public void validateTarget(String targetId) {
        if (targetId != null && !entriesById.containsKey(targetId)) {
            throw new SessionError(SessionErrorCode.NOT_FOUND, "Entry not found: " + targetId);
        }
    }

    /** Throw {@code already_exists} when the id is already used. */
    public void validateUnusedId(String id) {
        if (usedIds.contains(id)) {
            throw new SessionError(SessionErrorCode.ALREADY_EXISTS, "Session id already exists: " + id);
        }
    }

    /** Apply a validated mutation, advancing the shared sequence. */
    public void applyMutation(SessionMutation mutation) {
        long seq = mutation.seq();
        if (seq != sequence + 1) {
            invalidMutation("has non-consecutive seq " + seq);
        }
        switch (mutation) {
            case SessionMutation.Entry m -> applyEntry(m, seq);
            case SessionMutation.Record m -> applyRecord(m, seq);
            case SessionMutation.Lane m -> applyLane(m, seq);
            case SessionMutation.FactName m -> applyFactName(m, seq);
            case SessionMutation.FactLabel m -> applyFactLabel(m, seq);
        }
    }

    // ── Reads ──────────────────────────────────────────────

    /** Get an entry by id, or {@code null}. */
    public Entry getEntry(String id) {
        return entriesById.get(id);
    }

    /** Find entries matching a query. */
    public List<Entry> findEntries(EntryQuery query) {
        var q = query == null ? EntryQuery.all() : query;
        assertValidLimit(q.limit());
        assertValidCursor(q.cursor());
        List<Entry> results = new ArrayList<>();
        for (var entry : ordered(entries, q.order())) {
            if (!matchesEntryQuery(entry, q)) {
                continue;
            }
            results.add(entry);
            if (q.limit() != null && results.size() >= q.limit()) {
                break;
            }
        }
        return results;
    }

    /** Find entries on the branch path from {@code start} toward the root. */
    public List<Entry> findEntriesOnBranch(EntryQuery query, BranchBounds bounds, String start) {
        var q = query == null ? EntryQuery.all() : query;
        var b = bounds == null ? BranchBounds.none() : bounds;
        assertValidLimit(q.limit());
        assertValidCursor(q.cursor());
        List<Entry> results = new ArrayList<>();
        if (q.order() == EntryOrder.OLDEST_FIRST) {
            var path = walkToRoot(start, null);
            for (int i = path.size() - 1; i >= 0; i--) {
                var entry = path.get(i);
                boolean reachedBound = entry.id().equals(b.stopAtId())
                    || entry.type().equals(b.stopAtType());
                if (matchesEntryQuery(entry, q)) {
                    results.add(entry);
                }
                if (reachedBound || (q.limit() != null && results.size() >= q.limit())) {
                    break;
                }
            }
        } else {
            for (var entry : walkToRoot(start, b)) {
                if (matchesEntryQuery(entry, q)) {
                    results.add(entry);
                }
                if (q.limit() != null && results.size() >= q.limit()) {
                    break;
                }
            }
        }
        return results;
    }

    /** Find lane records matching a query. */
    public List<LaneRecord> findRecords(RecordQuery query) {
        var q = query == null ? RecordQuery.all() : query;
        assertValidLimit(q.limit());
        assertValidCursor(q.afterSeq());
        List<LaneRecord> results = new ArrayList<>();
        for (var record : ordered(records, q.order())) {
            if (!matchesRecordQuery(record, q)) {
                continue;
            }
            results.add(record);
            if (q.limit() != null && results.size() >= q.limit()) {
                break;
            }
        }
        return results;
    }

    /** Unfinished operation starts for a lane, newest first. */
    public List<LaneRecord.OperationStarted> findOpenOperations(String lane, int limit) {
        if (limit < 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY, "limit must be a positive integer");
        }
        var open = openOperationsByLane.get(lane);
        List<LaneRecord.OperationStarted> result = open == null
            ? List.of() : new ArrayList<>(open.values());
        java.util.Collections.reverse(result);
        return limit == 0 ? result : result.subList(0, Math.min(limit, result.size()));
    }

    /** The session log, filtered by {@code afterSeq} and capped by {@code limit}. */
    public List<LogItem> getLog(LogOptions options) {
        var o = options == null ? LogOptions.none() : options;
        assertValidLimit(o.limit());
        assertValidCursor(o.afterSeq());
        List<LogItem> results = new ArrayList<>();
        for (var item : log) {
            if (o.afterSeq() != null && item.seq() <= o.afterSeq()) {
                continue;
            }
            results.add(item);
            if (o.limit() != null && results.size() >= o.limit()) {
                break;
            }
        }
        return results;
    }

    /** The session name, or {@code null}. */
    public String getName() {
        return name;
    }

    /** The label for an entry, or {@code null}. */
    public String getLabel(String id) {
        return labels.get(id);
    }

    /** Accumulated statistics. */
    public SessionStats getStats() {
        return new SessionStats(messageCount, cachedTokens, uncachedTokens, totalTokens, costTotal);
    }

    /**
     * Build the mutation sequence for forking this state (tree: everything;
     * branch: the selected path + scope-filtered labels). Copies keep their
     * original ids and timestamps; seq is renumbered from 1.
     */
    public List<SessionMutation> createForkMutations(ForkOptions options) {
        List<Entry> copiedEntries;
        List<LanePointer> forkLanes;
        if (options instanceof ForkOptions.Tree) {
            copiedEntries = findEntries(new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null));
            forkLanes = getLanes();
        } else {
            ForkOptions.Branch branch = (ForkOptions.Branch) options;
            String selectedEntryId = branch.entryId() != null ? branch.entryId() : requireLane("main");
            String targetId = null;
            if (selectedEntryId != null) {
                var entry = getEntry(selectedEntryId);
                if (entry == null || !(entry instanceof Entry.Message)) {
                    throw new SessionError(SessionErrorCode.INVALID_FORK_TARGET,
                        "Fork target is not a message entry: " + selectedEntryId);
                }
                boolean at = branch.position() == ForkOptions.Branch.Position.AT
                    || (branch.position() == null && branch.entryId() == null);
                targetId = at ? entry.id() : entry.parentId();
            }
            copiedEntries = targetId == null ? List.of() : findEntriesOnBranch(
                new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null),
                BranchBounds.from(targetId), targetId);
            forkLanes = List.of(new LanePointer("main", targetId));
        }

        List<SessionMutation> mutations = new ArrayList<>();
        long sequence = 1;
        for (var sourceEntry : copiedEntries) {
            Entry copied = sourceEntry.committed(sequence++, sourceEntry.parentId(), sourceEntry.timestamp());
            mutations.add(new SessionMutation.Entry(null, copied));
        }
        for (var pointer : forkLanes) {
            mutations.add(new SessionMutation.Lane(sequence++, pointer.lane(), pointer.leafId()));
        }
        if (name != null) {
            mutations.add(new SessionMutation.FactName(sequence++, name));
        }
        for (var entry : copiedEntries) {
            String label = labels.get(entry.id());
            if (label != null) {
                mutations.add(new SessionMutation.FactLabel(sequence++, entry.id(), label));
            }
        }
        return mutations;
    }

    // ── Mutation application ───────────────────────────────

    private void applyEntry(SessionMutation.Entry m, long seq) {
        var entry = m.entry();
        if (usedIds.contains(entry.id())) {
            invalidMutation("contains duplicate id " + entry.id());
        }
        if (m.lane() != null) {
            String leafId = lanes.get(m.lane());
            if (leafId == null && !lanes.containsKey(m.lane())) {
                invalidMutation("references missing lane " + m.lane());
            }
            if (!java.util.Objects.equals(entry.parentId(), leafId)) {
                invalidMutation("does not chain to the lane leaf");
            }
        }
        if (entry.parentId() != null && !entriesById.containsKey(entry.parentId())) {
            invalidMutation("references missing parent " + entry.parentId());
        }
        sequence = seq;
        usedIds.add(entry.id());
        entries.add(entry);
        entriesById.put(entry.id(), entry);
        if (m.lane() != null) {
            lanes.put(m.lane(), entry.id());
        }
        log.add(new LogItem.EntryItem(seq, entry));
        if (entry.type().equals("message")) {
            messageCount++;
        }
    }

    private void applyRecord(SessionMutation.Record m, long seq) {
        var record = m.record();
        if (!lanes.containsKey(record.lane())) {
            invalidMutation("references missing lane " + record.lane());
        }
        if (usedIds.contains(record.id())) {
            invalidMutation("contains duplicate id " + record.id());
        }
        sequence = seq;
        usedIds.add(record.id());
        records.add(record);
        if (record instanceof LaneRecord.OperationStarted started) {
            openOperationsByLane.computeIfAbsent(record.lane(), k -> new LinkedHashMap<>())
                .put(record.id(), started);
        } else if (record instanceof LaneRecord.OperationFinished finished) {
            var open = openOperationsByLane.get(record.lane());
            if (open != null) {
                open.remove(finished.runId());
            }
        }
        log.add(new LogItem.RecordItem(seq, record));
        if (record instanceof LaneRecord.UsageRecord usage) {
            cachedTokens += usage.usage().cacheRead();
            uncachedTokens += usage.usage().input() + usage.usage().cacheWrite();
            totalTokens += usage.usage().totalTokens();
            costTotal += usage.usage().cost().total();
        }
    }

    private void applyLane(SessionMutation.Lane m, long seq) {
        if (m.leafId() != null && !entriesById.containsKey(m.leafId())) {
            invalidMutation("references missing lane target " + m.leafId());
        }
        sequence = seq;
        lanes.put(m.lane(), m.leafId());
        log.add(new LogItem.LaneItem(seq, m.lane(), m.leafId()));
    }

    private void applyFactName(SessionMutation.FactName m, long seq) {
        sequence = seq;
        name = m.name();
        log.add(new LogItem.NameItem(seq, m.name()));
    }

    private void applyFactLabel(SessionMutation.FactLabel m, long seq) {
        if (!entriesById.containsKey(m.targetId())) {
            invalidMutation("references missing label target " + m.targetId());
        }
        sequence = seq;
        if (m.label() == null) {
            labels.remove(m.targetId());
        } else {
            labels.put(m.targetId(), m.label());
        }
        log.add(new LogItem.LabelItem(seq, m.targetId(), m.label()));
    }

    // ── Internals ──────────────────────────────────────────

    private List<Entry> walkToRoot(String start, BranchBounds bounds) {
        if (start == null) {
            return List.of();
        }
        Set<String> visited = new LinkedHashSet<>();
        List<Entry> path = new ArrayList<>();
        Entry current = entriesById.get(start);
        if (current == null) {
            throw new SessionError(SessionErrorCode.NOT_FOUND, "Entry not found: " + start);
        }
        while (current != null) {
            if (!visited.add(current.id())) {
                throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                    "Session branch contains a cycle at " + current.id());
            }
            path.add(current);
            if (current.id().equals(bounds != null ? bounds.stopAtId() : null)
                || current.type().equals(bounds != null ? bounds.stopAtType() : null)
                || current.parentId() == null) {
                break;
            }
            current = entriesById.get(current.parentId());
            if (current == null) {
                throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                    "Entry not found: " + start);
            }
        }
        return path;
    }

    private boolean matchesEntryQuery(Entry entry, EntryQuery query) {
        return (query.type() == null || query.type().equals(entry.type()))
            && (query.customType() == null
                || (entry.type().equals("custom")
                    && query.customType().equals(((Entry.Custom) entry).customType())))
            && (query.cursor() == null
                || (query.order() == EntryOrder.OLDEST_FIRST
                    ? entry.seq() > query.cursor().afterSeq()
                    : entry.seq() < query.cursor().afterSeq()));
    }

    private boolean matchesRecordQuery(LaneRecord record, RecordQuery query) {
        boolean runIdMatches;
        if (query.runId() == null) {
            runIdMatches = true;
        } else if (record instanceof LaneRecord.OperationStarted started) {
            runIdMatches = started.id().equals(query.runId());
        } else if (record instanceof LaneRecord.OperationFinished finished) {
            runIdMatches = query.runId().equals(finished.runId());
        } else if (record instanceof LaneRecord.StepAttempt step) {
            runIdMatches = query.runId().equals(step.runId());
        } else if (record instanceof LaneRecord.ToolStarted tool) {
            runIdMatches = query.runId().equals(tool.runId());
        } else if (record instanceof LaneRecord.QueueEnqueued enqueued) {
            runIdMatches = enqueued.runId() != null && query.runId().equals(enqueued.runId());
        } else if (record instanceof LaneRecord.QueueCancelled cancelled) {
            runIdMatches = cancelled.runId() != null && query.runId().equals(cancelled.runId());
        } else if (record instanceof LaneRecord.WriteDeferred deferred) {
            runIdMatches = query.runId().equals(deferred.runId());
        } else if (record instanceof LaneRecord.UsageRecord usage) {
            runIdMatches = usage.runId() != null && query.runId().equals(usage.runId());
        } else {
            runIdMatches = false;
        }
        return (query.lane() == null || query.lane().equals(record.lane()))
            && (query.type() == null || query.type().equals(record.type()))
            && runIdMatches
            && (query.operationKind() == null
                || (record instanceof LaneRecord.OperationStarted started
                    && started.intent() != null
                    && kindValue(started.intent()).equals(query.operationKind().value())))
            && (query.afterSeq() == null || record.seq() > query.afterSeq());
    }

    private static String kindValue(LaneRecord.OperationStarted.Intent intent) {
        return switch (intent) {
            case LaneRecord.OperationStarted.Run r -> "run";
            case LaneRecord.OperationStarted.Compaction c -> "compaction";
            case LaneRecord.OperationStarted.Navigation n -> "navigation";
        };
    }

    private static <T> Iterable<T> ordered(List<T> items, EntryOrder order) {
        if (order == EntryOrder.OLDEST_FIRST) {
            return items;
        }
        List<T> reversed = new ArrayList<>(items);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static void assertValidLimit(Integer limit) {
        if (limit != null && limit <= 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY, "limit must be a positive integer");
        }
    }

    private static void assertValidCursor(EntryCursor cursor) {
        if (cursor != null && cursor.afterSeq() < 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY,
                "cursor sequence must be a non-negative integer");
        }
    }

    private static void assertValidCursor(Long afterSeq) {
        if (afterSeq != null && afterSeq < 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY,
                "cursor sequence must be a non-negative integer");
        }
    }

    private static void invalidMutation(String message) {
        throw new SessionError(SessionErrorCode.INVALID_ENTRY,
            "Invalid session mutation: " + message);
    }
}
