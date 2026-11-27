package com.pijava.agent.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Builds and applies per-request tool loadout changes (B288). */
public final class ToolLoadouts {
    private ToolLoadouts() {
    }

    /**
     * Builds the loadout visible to tools from the registry and active names.
     *
     * @param registry tool registry
     * @param activeNames names of active tools
     * @return loadout passed to {@link AgentTool#prepareLoadout(ToolLoadout)}
     */
    public static ToolLoadout of(ToolRegistry registry, Collection<String> activeNames) {
        List<AgentTool<?, ?>> all = List.copyOf(registry.all());
        Set<String> active = new LinkedHashSet<>(activeNames);
        Function<String, ToolExposure> exposure = name -> {
            AgentTool<?, ?> tool = registry.get(name);
            return tool == null ? ToolExposure.DIRECT : tool.exposure();
        };
        Function<String, ToolNamespace> namespace = name -> {
            AgentTool<?, ?> tool = registry.get(name);
            return tool == null ? null : tool.namespace();
        };
        Function<String, List<String>> guidelines = name -> {
            AgentTool<?, ?> tool = registry.get(name);
            return tool == null ? List.of() : tool.promptGuidelines();
        };
        return new ToolLoadout(all, List.copyOf(all), active, exposure, namespace, guidelines);
    }

    /**
     * Applies all hooks to a list of tools, preserving registry order.
     *
     * @param tools tools being declared
     * @param loadout current loadout
     * @return tools with rewritten descriptions and hidden declarations removed
     */
    public static List<AgentTool<?, ?>> apply(List<AgentTool<?, ?>> tools, ToolLoadout loadout) {
        Map<String, String> descriptions = new HashMap<>();
        Set<String> hidden = new HashSet<>();
        for (AgentTool<?, ?> tool : tools) {
            tool.prepareLoadout(loadout).ifPresent(changes -> {
                descriptions.putAll(changes.descriptions());
                hidden.addAll(changes.hiddenDeclarations());
            });
        }
        List<AgentTool<?, ?>> result = new ArrayList<>();
        for (AgentTool<?, ?> tool : tools) {
            if (hidden.contains(tool.name())) {
                continue;
            }
            String description = descriptions.get(tool.name());
            result.add(description == null ? tool : new ToolLoadoutView<>(tool, description));
        }
        return List.copyOf(result);
    }
}
