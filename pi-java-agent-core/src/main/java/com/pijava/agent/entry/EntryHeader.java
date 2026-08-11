package com.pijava.agent.entry;

import java.time.Instant;

/**
 * Shared identity fields for all {@link Entry} subtypes.
 *
 * <p>Extracted into a separate record to eliminate field repetition
 * across the 7 sealed subtypes.</p>
 *
 * @param id        unique entry identifier (UUID)
 * @param seq       monotonic sequence number
 * @param parentId  parent entry ID, or empty string for root
 * @param timestamp when this entry was created
 */
public record EntryHeader(
    String id,
    long seq,
    String parentId,
    Instant timestamp
) {}
