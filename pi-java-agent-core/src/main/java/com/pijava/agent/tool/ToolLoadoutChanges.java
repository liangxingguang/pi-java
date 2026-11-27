package com.pijava.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * Changes a tool applies to the request loadout (B288).
 *
 * @param descriptions replacement descriptions keyed by tool name
 * @param hiddenDeclarations tool names whose declarations must be removed
 */
public record ToolLoadoutChanges(Map<String, String> descriptions, List<String> hiddenDeclarations) {
    /** Returns an empty change set. */
    public static ToolLoadoutChanges empty() {
        return new ToolLoadoutChanges(Map.of(), List.of());
    }
}
