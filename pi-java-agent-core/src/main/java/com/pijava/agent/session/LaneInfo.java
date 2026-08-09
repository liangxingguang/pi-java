package com.pijava.agent.session;

import java.time.Instant;

/**
 * Metadata about a lane in a session.
 *
 * @param name      lane identifier
 * @param createdAt when the lane was created
 * @param entryCount approximate number of entries in this lane
 */
public record LaneInfo(
    String name,
    Instant createdAt,
    long entryCount
) {}
