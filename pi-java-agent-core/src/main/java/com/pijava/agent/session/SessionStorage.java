package com.pijava.agent.session;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.NewRecord;

/**
 * Per-session persistence contract (aligned with pi {@code SessionStorage}).
 *
 * <p>Methods are synchronous; blocking I/O is carried by the caller's virtual
 * thread. Writes are serialized per storage (JSONL tail chain / SQLite serial
 * queue) and validated by {@link SessionState} semantics.</p>
 *
 * @param <TMetadata> the backend-specific session metadata type
 */
public interface SessionStorage<TMetadata extends SessionMetadata> {

    /** Session metadata. */
    TMetadata getMetadata();

    // ── Lanes ──────────────────────────────────────────────

    /** All lanes with their current leaves. */
    List<LanePointer> getLanes();

    /** Create a lane at entry {@code at} (may be null). */
    void createLane(String lane, String at);

    /** Move a lane to entry {@code to} (may be null). */
    void moveLane(String lane, String to);

    // ── Writes ─────────────────────────────────────────────

    /**
     * Append an entry. {@code seq}/{@code parentId}/{@code timestamp} are
     * storage-assigned; the committed entry is returned.
     */
    <T extends Entry> T appendEntry(ProvisionedEntry<T> entry, String lane);

    /** Append a lane record. {@code seq}/{@code timestamp} are storage-assigned. */
    <T extends LaneRecord> T appendRecord(NewRecord<T> record);

    // ── Reads ──────────────────────────────────────────────

    /** Get an entry by id, or {@code null}. */
    Entry getEntry(String id);

    /** Find entries matching a query. */
    List<Entry> findEntries(EntryQuery query);

    /**
     * Find entries on the branch path from {@code start} toward the root.
     * {@code start} is mandatory here (defaulting to a lane leaf is view sugar).
     */
    List<Entry> findEntriesOnBranch(EntryQuery query, BranchBounds bounds, String start);

    /** Find lane records matching a query. */
    List<LaneRecord> findRecords(RecordQuery query);

    /** Unfinished operation starts for a lane, newest first. */
    List<LaneRecord.OperationStarted> findOpenOperations(String lane, int limit);

    /** The session log. */
    List<LogItem> getLog(LogOptions options);

    // ── Global facts (latest wins, not branch-scoped) ───────

    /** The session name, or {@code null}. */
    String getName();

    /** Set (or clear) the session name. */
    void setName(String name);

    /** The label for an entry, or {@code null}. */
    String getLabel(String id);

    /** Set (or clear) an entry label. */
    void setLabel(String id, String label);

    // ── Stats ──────────────────────────────────────────────

    /** Accumulated session statistics. */
    SessionStats getStats();

    /** Wait for all queued writes to complete. */
    void drain();

    /** Release resources (JSONL: no-op; SQLite: release lease + stop heartbeat). */
    void close();
}
