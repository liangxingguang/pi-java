package com.pijava.agent.session;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;

/**
 * A persisted log item with its sequence number (aligned with pi
 * {@code LogItem} union). Structurally parallel to {@link SessionMutation},
 * but read-side: mutations are pending writes, log items are committed.
 */
public sealed interface LogItem {

    /** The sequence number of this log item. */
    long seq();

    /** An entry write. */
    record EntryItem(long seq, Entry entry) implements LogItem {}

    /** A lane record write. */
    record RecordItem(long seq, LaneRecord record) implements LogItem {}

    /** A lane create/move. */
    record LaneItem(long seq, String lane, String leafId) implements LogItem {}

    /** A session name fact (name may be null = cleared). */
    record NameItem(long seq, String name) implements LogItem {}

    /** An entry label fact (label may be null = cleared). */
    record LabelItem(long seq, String targetId, String label) implements LogItem {}
}
