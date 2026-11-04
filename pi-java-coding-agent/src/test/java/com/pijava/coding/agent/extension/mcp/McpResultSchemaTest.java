package com.pijava.coding.agent.extension.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code CallToolResult} output schema of every MCP tool
 * ({@code tools.ts:104-176}).
 */
class McpResultSchemaTest {

    private static Map<String, Object> contentBlock() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("type", Map.of("enum",
                List.of("text", "image", "audio", "resource_link", "resource")));
        properties.put("text", Map.of("type", "string"));
        properties.put("data", Map.of("type", "string"));
        properties.put("mimeType", Map.of("type", "string"));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("type"), "additionalProperties", true);
    }

    @Test
    void alwaysWrapsStructuredContentAndDropsMeta() {
        var schema = McpResultSchema.createMcpResultSchema(null);
        var properties = new LinkedHashMap<String, Object>();
        properties.put("content", Map.of("type", "array", "items", contentBlock()));
        // Servers may send structured content without a schema; any JSON value is allowed.
        properties.put("structuredContent", Map.of());
        properties.put("isError", Map.of("type", "boolean"));
        assertThat(schema).isEqualTo(Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("content"),
                // Fields newer protocol versions add.
                "additionalProperties", true));
    }

    // JSON schemas are shape-agnostic maps; the test knows each level's shape.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> structuredOf(Map<String, Object> outputSchema) {
        return (Map<String, Object>) ((Map<String, Object>)
                McpResultSchema.createMcpResultSchema(outputSchema).get("properties"))
                .get("structuredContent");
    }

    // JSON schemas are shape-agnostic maps; the test knows each level's shape.
    @SuppressWarnings("unchecked")
    @Test
    void relocatesReferencesIntoStructuredContent() {
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "object");
        item.put("properties", new LinkedHashMap<>(Map.of("id", Map.of("type", "string"))));
        var definitions = new LinkedHashMap<String, Object>();
        definitions.put("Item", item);
        var properties = new LinkedHashMap<String, Object>();
        properties.put("item", Map.of("$ref", "#/$defs/Item"));
        var output = new LinkedHashMap<String, Object>();
        output.put("type", "object");
        output.put("$defs", definitions);
        output.put("properties", properties);

        var wrapped = structuredOf(output);
        // The reference points into the structured content now.
        assertThat((Map<String, Object>) ((Map<String, Object>) wrapped.get("properties"))
                .get("item"))
                .containsEntry("$ref", "#/properties/structuredContent/$defs/Item");
        assertThat(wrapped.get("$defs")).isEqualTo(output.get("$defs"));
        var defs = (Map<String, Object>) wrapped.get("$defs");
        var itemSchema = (Map<String, Object>) defs.get("Item");
        assertThat((Map<String, Object>) itemSchema.get("properties")).containsKey("id");
    }

    @Test
    void leavesReferencesInsideDataKeywordsAlone() {
        var output = new LinkedHashMap<String, Object>();
        output.put("enum", List.of("#/not-a-reference"));
        output.put("default", "#/$defs/Other");
        output.put("const", "#/value");
        output.put("examples", List.of("#/also-not"));

        var wrapped = structuredOf(output);
        assertThat(wrapped).containsEntry("enum", List.of("#/not-a-reference"))
                .containsEntry("default", "#/$defs/Other")
                .containsEntry("const", "#/value")
                .containsEntry("examples", List.of("#/also-not"));
    }

    @Test
    void mapKeywordNamesAreNamesNotKeywords() {
        var output = new LinkedHashMap<String, Object>();
        output.put("properties", new LinkedHashMap<>(Map.of("$ref", Map.of("type", "string"))));

        var wrapped = structuredOf(output);
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) wrapped.get("properties");
        assertThat(properties).containsOnlyKeys("$ref");
        assertThat(properties.get("$ref")).isEqualTo(Map.of("type", "string"));
    }
}
