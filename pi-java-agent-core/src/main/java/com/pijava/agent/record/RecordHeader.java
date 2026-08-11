package com.pijava.agent.record;

import java.time.Instant;

/**
 * Shared identity fields for all {@link LaneRecord} subtypes.
 *
 * @param seq       monotonic sequence number
 * @param timestamp when this record was created
 */
public record RecordHeader(
    long seq,
    Instant timestamp
) {}
