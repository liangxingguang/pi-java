package com.pijava.agent.harness;

import java.util.List;

/**
 * Immutable point-in-time snapshot of the entire session.
 */
public record SessionSnapshot(
    String name,
    String model,
    String phase,
    long totalTokens,
    int turnCount,
    List<String> activeTools,
    List<LaneInfo> lanes
) {}
