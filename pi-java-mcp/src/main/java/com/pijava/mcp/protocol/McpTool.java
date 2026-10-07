package com.pijava.mcp.protocol;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A tool a server lists in {@code tools/list} ({@code types.ts:76-85}).
 *
 * @param name         tool name
 * @param title        optional title
 * @param description  optional description
 * @param inputSchema  parameter JSON Schema
 * @param outputSchema optional result JSON Schema
 * @param annotations  optional annotations
 * @param execution    optional execution metadata
 * @param meta         optional {@code _meta}
 */
public record McpTool(
        String name,
        @Nullable String title,
        @Nullable String description,
        Map<String, Object> inputSchema,
        @Nullable Map<String, Object> outputSchema,
        @Nullable ToolAnnotations annotations,
        @Nullable ToolExecution execution,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) {
}
