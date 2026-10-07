package com.pijava.coding.agent.extension.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.coding.agent.extension.mcp.McpToolDefinition.Annotations;
import com.pijava.mcp.protocol.McpTool;

/**
 * The JSON schemas of an MCP tool's definition (pi {@code tools.ts:106-122,236-258}).
 */
public final class McpResultSchema {

    private McpResultSchema() {
    }

    /**
     * Output schema of every MCP tool: the {@code CallToolResult} scripts receive, with the
     * tool's own output schema as {@code structuredContent} ({@code tools.ts:111-122}).
     *
     * @param structuredContentSchema the tool's output schema, when it declares one
     * @return the schema
     */
    public static Map<String, Object> createMcpResultSchema(
            @Nullable Map<String, Object> structuredContentSchema) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("content", Map.of("type", "array", "items", Map.of("type", "object")));
        if (structuredContentSchema != null) {
            properties.put("structuredContent", structuredContentSchema);
        }
        properties.put("isError", Map.of("type", "boolean"));
        properties.put("_meta", Map.of("type", "object"));
        return Map.of(
                "type", "object",
                "properties", Map.copyOf(properties),
                "required", List.of("content"));
    }

    /**
     * Tool input schemas must be objects. MCP servers may omit {@code type}, and some providers
     * reject object schemas without {@code properties} ({@code tools.ts:236-246}).
     *
     * @param schema the server's schema
     * @return the schema, completed
     */
    public static Map<String, Object> toParameters(Map<String, Object> schema) {
        var out = new LinkedHashMap<String, Object>(schema);
        out.putIfAbsent("type", "object");
        out.putIfAbsent("properties", Map.of());
        return Map.copyOf(out);
    }

    /**
     * The boolean hints of an MCP tool's annotations, or {@code null} when it has none
     * ({@code tools.ts:251-258}).
     *
     * @param tool the tool
     * @return the hints, or {@code null}
     */
    public static @Nullable Annotations toAnnotations(McpTool tool) {
        var annotations = tool.annotations();
        if (annotations == null) {
            return null;
        }
        var readOnly = annotations.readOnlyHint();
        var destructive = annotations.destructiveHint();
        var idempotent = annotations.idempotentHint();
        var openWorld = annotations.openWorldHint();
        if (readOnly == null && destructive == null && idempotent == null && openWorld == null) {
            return null;
        }
        return new Annotations(readOnly, destructive, idempotent, openWorld);
    }
}
