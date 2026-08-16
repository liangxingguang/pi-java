package com.pijava.agent.session;


import com.pijava.agent.record.LaneRecord;

/**
 * A single persistable session mutation (aligned with pi's
 * {@code SessionMutation} union). {@code seq} is strictly increasing.
 */
public sealed interface SessionMutation {

    /** The sequence number consumed by this mutation. */
    long seq();

    /** Append an entry (with an optional lane for chain validation). */
    record Entry(String lane, com.pijava.agent.entry.Entry entry) implements SessionMutation {
        @Override
        public long seq() {
            return entry.seq();
        }
    }

    /** Append a lane record. */
    record Record(LaneRecord record) implements SessionMutation {
        @Override
        public long seq() {
            return record.seq();
        }
    }

    /** Create or move a lane. */
    record Lane(long seq, String lane, String leafId) implements SessionMutation {}

    /** Set (or clear, when {@code name} is null) the session name. */
    record FactName(long seq, String name) implements SessionMutation {}

    /** Set (or clear, when {@code label} is null) an entry label. */
    record FactLabel(long seq, String targetId, String label) implements SessionMutation {}
}
