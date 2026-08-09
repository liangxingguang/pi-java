package com.pijava.agent.session;

import java.util.List;

import com.pijava.agent.harness.Entry;
import com.pijava.agent.harness.LaneRecord;

/**
 * Per-session persistence interface.
 *
 * <p>Manages lanes, entries, and records for a single session.
 * Implementations may be in-memory, SQLite-backed, or file-based.</p>
 *
 * @param <TMetadata> the metadata type for this session
 */
public interface SessionStorage<TMetadata> {

    /** Get session metadata. */
    TMetadata getMetadata();

    /** List all lanes. */
    List<LaneInfo> getLanes();

    /** Create a new lane. */
    void createLane(String lane, String at);

    /** Move a lane to a new position. */
    void moveLane(String lane, String to);

    /** Append an entry to a lane. */
    <T extends Entry> T appendEntry(T entry, String lane);

    /** Append a lane record. */
    <T extends LaneRecord> T appendRecord(T record);

    /** Get an entry by ID. */
    Entry getEntry(String id);

    /** Find entries matching a query. */
    List<Entry> findEntries(EntryQuery query);

    /** Close and release resources. */
    void close();
}
