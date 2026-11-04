package com.pijava.coding.agent.extension.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.agent.tool.ToolAnnotations;
import com.pijava.mcp.protocol.McpTool;

/**
 * The JSON schemas of an MCP tool's definition (pi {@code tools.ts:106-122,236-258}).
 */
public final class McpResultSchema {

    /** Keywords whose values are data, so a {@code $ref} key in them is not a reference. */
    private static final java.util.Set<String> SCHEMA_DATA_KEYWORDS =
            java.util.Set.of("const", "enum", "default", "examples");

    /** Keywords whose values map names to schemas, so their keys are names, not keywords. */
    private static final java.util.Set<String> SCHEMA_MAP_KEYWORDS = java.util.Set.of(
            "properties", "patternProperties", "dependentSchemas",
            "dependencies", "$defs", "definitions");

    /** One content block, copied from pi's {@code CONTENT_BLOCK_SCHEMA} ({@code tools.ts:104-114}). */
    private static final Map<String, Object> CONTENT_BLOCK_SCHEMA = contentBlockSchema();

    private McpResultSchema() {
    }

    private static Map<String, Object> contentBlockSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("type", Map.of("enum",
                List.of("text", "image", "audio", "resource_link", "resource")));
        properties.put("text", Map.of("type", "string"));
        properties.put("data", Map.of("type", "string"));
        properties.put("mimeType", Map.of("type", "string"));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("type"), "additionalProperties", true);
    }

    /**
     * Output schema of every MCP tool: the {@code CallToolResult} scripts receive, with the
     * tool's own output schema as {@code structuredContent} ({@code tools.ts:104-176}).
     *
     * @param structuredContentSchema the tool's output schema, when it declares one
     * @return the schema
     */
    public static Map<String, Object> createMcpResultSchema(
            @Nullable Map<String, Object> structuredContentSchema) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("content", Map.of("type", "array", "items", CONTENT_BLOCK_SCHEMA));
        // Servers may send structured content without declaring a schema for it,
        // and it may be any JSON value.
        properties.put("structuredContent", structuredContentSchema == null
                ? Map.of()
                : relocateSchema(structuredContentSchema, "#/properties/structuredContent"));
        properties.put("isError", Map.of("type", "boolean"));
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("content"));
        // Fields newer protocol versions add.
        schema.put("additionalProperties", true);
        return schema;
    }

    /**
     * Rewrite local {@code $ref}s so they still resolve once the schema sits under
     * {@code structuredContent} ({@code tools.ts:116-176}).
     *
     * <p>JSON object keys are strings; the wildcard capture cannot say so.</p>
     */
    @SuppressWarnings("unchecked")
    private static Object relocateSchema(Object schema, String pointer) {
        if (schema instanceof List<?> list) {
            return list.stream().map(item -> relocateSchema(item, pointer)).toList();
        }
        if (!(schema instanceof Map<?, ?> raw)) {
            return schema;
        }
        var relocated = new LinkedHashMap<String, Object>();
        for (var entry : ((Map<String, Object>) raw).entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if ("$ref".equals(key) && value instanceof String reference
                    && (reference.equals("#") || reference.startsWith("#/"))) {
                relocated.put(key, pointer + reference.substring(1));
            } else if (SCHEMA_DATA_KEYWORDS.contains(key)) {
                relocated.put(key, value);
            } else if (SCHEMA_MAP_KEYWORDS.contains(key) && value instanceof Map<?, ?> map) {
                var nested = new LinkedHashMap<String, Object>();
                for (var sub : ((Map<String, Object>) map).entrySet()) {
                    nested.put(sub.getKey(), relocateSchema(sub.getValue(), pointer));
                }
                relocated.put(key, nested);
            } else {
                relocated.put(key, relocateSchema(value, pointer));
            }
        }
        return relocated;
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
     * ({@code tools.ts:251-258})。批 30（B203）：返回 agent-core
     * {@link ToolAnnotations}（{@code McpToolDefinition.Annotations} 随 sidecar 收编删除）。
     *
     * @param tool the tool
     * @return the hints, or {@code null}
     */
    public static @Nullable ToolAnnotations toAnnotations(McpTool tool) {
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
        return new ToolAnnotations(readOnly, destructive, idempotent, openWorld);
    }
}
