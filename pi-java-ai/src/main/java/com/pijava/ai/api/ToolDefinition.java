package com.pijava.ai.api;

import java.util.Map;

/**
 * Definition of a tool that the LLM can call.
 *
 * @param name        tool identifier
 * @param description human-readable description
 * @param inputSchema JSON Schema for the tool's arguments
 */
public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema
) {
    /** Compact constructor that defensively copies the {@code inputSchema} map. */
    public ToolDefinition {
        inputSchema = Map.copyOf(inputSchema);
    }
}
