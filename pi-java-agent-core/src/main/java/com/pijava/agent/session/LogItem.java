package com.pijava.agent.session;

import java.time.Instant;

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

    /** A lane record write. 见 {@link SessionMutation.Lane} 的补身份说明（{@code docs/12 §6 D2}）。 */
    record RecordItem(long seq, String parentId, LaneRecord record) implements LogItem {}

    /** A lane create/move. */
    record LaneItem(long seq, String parentId, Instant timestamp, String lane, String leafId)
        implements LogItem {}

    /** A session name fact (name may be null = cleared). */
    record NameItem(long seq, String parentId, Instant timestamp, String name) implements LogItem {}

    /** An entry label fact (label may be null = cleared). */
    record LabelItem(long seq, String parentId, Instant timestamp, String targetId, String label)
        implements LogItem {}
}
