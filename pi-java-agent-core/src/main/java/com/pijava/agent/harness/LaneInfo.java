package com.pijava.agent.harness;

import java.util.List;

/**
 * Structured lane metadata used within snapshots.
 * Note: different from {@code com.pijava.agent.session.LaneInfo} which is a storage-layer type.
 */
public record LaneInfo(
    String name,
    String leafId,
    OperationInfo operation
) {
    /** Information about the current operation on this lane. */
    public record OperationInfo(
        String id,
        String kind,   // "run" | "compaction" | "navigation"
        String status  // "running" | "suspended" | "aborting"
    ) {}

    /** Snapshot of the three scheduling queues. */
    public record Queues(
        List<QueuedItem> steer,
        List<QueuedItem> followUp,
        List<QueuedItem> nextRun
    ) {}

    /** A queued steer/followUp/nextRun item. */
    public record QueuedItem(String prompt, long seq) {}
}
