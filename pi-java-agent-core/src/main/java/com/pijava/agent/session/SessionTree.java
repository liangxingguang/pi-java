package com.pijava.agent.session;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;

/**
 * Lane-scoped read/write facade (aligned with pi {@code SessionTree}).
 * Branch queries default their start to the view's lane leaf.
 */
public interface SessionTree {

    /** The current leaf entry id of this lane, or {@code null}. */
    String getLeafId();

    /** Get an entry by id, or {@code null}. */
    Entry getEntry(String id);

    /** Session statistics. */
    SessionStats getStats();

    /** The session name, or {@code null}. */
    String getName();

    /** Set (or clear) the session name. */
    void setName(String name);

    /** The label for an entry, or {@code null}. */
    String getLabel(String targetId);

    /** Set (or clear) an entry label. */
    void setLabel(String targetId, String label);

    /** Session-wide entries, all branches, sequence order. */
    List<Entry> findEntries(EntryQuery query);

    /** The first matching entry, or {@code null}. */
    Entry findEntry(EntryQuery query);

    /** Branch-scoped entries: the path from start toward root. */
    List<Entry> findEntriesOnBranch(EntryQuery query, BranchBounds bounds);

    /** The first branch-scoped match, or {@code null}. */
    Entry findEntryOnBranch(EntryQuery query, BranchBounds bounds);

    /**
     * Append a message to this lane and return the persisted entry id.
     */
    String appendMessage(Message message);

    /** Append a custom entry and return the persisted entry id. */
    String appendCustomEntry(String customType, Map<String, Object> data);
}