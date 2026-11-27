package com.pijava.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The set of tools visible during one request, passed to
 * {@link AgentTool#prepareLoadout(ToolLoadout)} (B288).
 *
 * @param declared tools whose declarations are currently sent to the model
 * @param callable tools callable from a script or direct tool call
 * @param active names of active tools
 * @param exposureOf exposure of a tool by name
 * @param namespaceOf namespace of a tool by name
 * @param guidelinesOf prompt guidelines of a tool by name
 */
public record ToolLoadout(
        List<AgentTool<?, ?>> declared,
        List<AgentTool<?, ?>> callable,
        Set<String> active,
        Function<String, ToolExposure> exposureOf,
        Function<String, ToolNamespace> namespaceOf,
        Function<String, List<String>> guidelinesOf) {
}
