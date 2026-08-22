package com.pijava.ai.api;

import java.util.List;
import java.util.Map;

/**
 * Definition of a tool that the LLM can call.
 *
 * @param name            tool identifier
 * @param description     human-readable description
 * @param inputSchema     JSON Schema for the tool's arguments
 * @param label           human-readable label for UI display (pi {@code label}; defaults to {@code name})
 * @param promptSnippet   one-line snippet for the system prompt's Available-tools section
 *                        (pi {@code promptSnippet}; falls back to {@code description} when absent)
 * @param promptGuidelines guideline bullets appended to the system prompt when this tool is active
 * @param renderShell     whether the UI renders the standard shell or the tool renders itself
 *                        (pi {@code renderShell: "default"|"self"}; default {@code "default"})
 */
public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema,
    String label,
    String promptSnippet,
    List<String> promptGuidelines,
    String renderShell
) {
    /** Compact constructor that defensively copies collections and defaults nulls. */
    public ToolDefinition {
        inputSchema = Map.copyOf(inputSchema);
        promptGuidelines = List.copyOf(promptGuidelines);
        label = label == null ? name : label;
        renderShell = renderShell == null ? "default" : renderShell;
    }

    /** Convenience constructor for tools without rendering metadata. */
    public ToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema
    ) {
        this(name, description, inputSchema, name, null, List.of(), "default");
    }
}
