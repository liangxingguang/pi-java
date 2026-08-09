package com.pijava.agent.session;

/**
 * Query parameters for finding entries.
 *
 * <p>All fields are optional — a field with its default value
 * is not used as a filter.</p>
 *
 * @param limit    maximum entries to return (0 = no limit)
 * @param beforeId return entries before this ID (empty = no filter)
 * @param afterId  return entries after this ID (empty = no filter)
 * @param type     filter by entry type (empty = all types)
 */
public record EntryQuery(
    int limit,
    String beforeId,
    String afterId,
    String type
) {
    /** Return all entries with no filtering. */
    public static EntryQuery all() {
        return new EntryQuery(0, "", "", "");
    }

    /** Return the most recent N entries. */
    public static EntryQuery last(int count) {
        return new EntryQuery(count, "", "", "");
    }
}
