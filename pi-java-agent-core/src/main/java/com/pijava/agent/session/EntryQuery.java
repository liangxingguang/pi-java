package com.pijava.agent.session;

/**
 * Query parameters for finding entries (aligned with pi {@code EntryQuery}).
 * A {@code null} {@code limit} means unlimited, matching pi's optional field.
 *
 * @param type       entry discriminant, {@code null} = all types
 * @param customType only valid when {@code type == "custom"}
 * @param order      default {@link EntryOrder#NEWEST_FIRST}
 * @param limit      positive maximum results, {@code null} = unlimited
 * @param cursor     order-agnostic seq filter (newest: seq &lt; afterSeq; oldest: seq &gt; afterSeq)
 */
public record EntryQuery(
    String type,
    String customType,
    EntryOrder order,
    Integer limit,
    EntryCursor cursor
) {

    /** Return all entries, newest first, unlimited. */
    public static EntryQuery all() {
        return new EntryQuery(null, null, EntryOrder.NEWEST_FIRST, null, null);
    }

    /** Return the most recent {@code count} entries. */
    public static EntryQuery last(int count) {
        return new EntryQuery(null, null, EntryOrder.NEWEST_FIRST, count, null);
    }
}
