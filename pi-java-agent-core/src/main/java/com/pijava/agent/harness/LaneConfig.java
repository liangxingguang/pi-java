package com.pijava.agent.harness;

import java.util.Map;

/**
 * Configuration for creating a new lane.
 *
 * @param name       lane identifier
 * @param parentId   parent entry ID to branch from (empty = root)
 * @param metadata   arbitrary key-value metadata
 */
public record LaneConfig(
    String name,
    String parentId,
    Map<String, Object> metadata
) {
    public LaneConfig {
        metadata = Map.copyOf(metadata);
    }

    /** Create a lane with default settings. */
    public static LaneConfig of(String name) {
        return new LaneConfig(name, "", Map.of());
    }
}
