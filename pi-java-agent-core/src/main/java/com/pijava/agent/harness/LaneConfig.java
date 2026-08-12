package com.pijava.agent.harness;

import java.util.Set;

import com.pijava.agent.tool.AgentTool;

/**
 * Configuration for creating a new lane.
 *
 * @param name          unique lane name
 * @param parentLeafId  optional parent leaf for branching (empty = root)
 * @param activeTools   tools enabled for this lane (null = inherit harness tools)
 * @param systemPrompt  lane-specific system prompt override (null = inherit harness prompt)
 */
public record LaneConfig(
    String name,
    String parentLeafId,
    Set<AgentTool<?, ?>> activeTools,
    String systemPrompt
) {
    public LaneConfig {
        if (activeTools != null) {
            activeTools = Set.copyOf(activeTools);
        }
    }

    /** Create a lane with default settings (inherit harness tools + prompt). */
    public static LaneConfig of(String name) {
        return new LaneConfig(name, null, null, null);
    }
}
