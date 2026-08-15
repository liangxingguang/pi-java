package com.pijava.agent.harness;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;

/**
 * Immutable point-in-time snapshot of a lane.
 *
 * @param lane          lane name
 * @param transcript    current transcript entries
 * @param leafId        current leaf entry id
 * @param operation     current operation info (null if idle)
 * @param queues        queue state
 * @param pendingWrites entries pending persistence
 * @param faulted       whether the lane is in a faulted state
 */
public record LaneSnapshot(
    String lane,
    List<Entry> transcript,
    String leafId,
    LaneInfo.OperationInfo operation,
    LaneInfo.Queues queues,
    List<Entry> pendingWrites,
    boolean faulted
) {}
