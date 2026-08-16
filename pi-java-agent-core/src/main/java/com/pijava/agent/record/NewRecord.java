package com.pijava.agent.record;

/**
 * A lane record whose {@code seq}/{@code timestamp} are assigned by the
 * storage on commit (aligned with pi's {@code NewRecord} type alias).
 *
 * @param <T> the concrete record type
 */
public final class NewRecord<T extends LaneRecord> {

    private final T record;

    /** Wrap a record pending storage-assigned identity fields. */
    public NewRecord(T record) {
        this.record = record;
    }

    /** The record to persist. */
    public T record() {
        return record;
    }
}
