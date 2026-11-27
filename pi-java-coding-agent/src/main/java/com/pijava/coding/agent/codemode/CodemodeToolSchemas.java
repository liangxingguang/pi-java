package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.codemode.api.CodeModeDeclarations;
import com.pijava.codemode.api.CodeModeSchemaTypes;

/** JSON-schema helpers shared by the codemode description and discovery globals. */
final class CodemodeToolSchemas {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The default output schema: tools without an output schema resolve to a string. */
    static final JsonNode TEXT_OUTPUT = JsonNodeFactory.instance.objectNode().put("type", "string");

    private CodemodeToolSchemas() {
    }

    /** Converts a map-shaped JSON schema into a Jackson tree. */
    @SuppressWarnings("unchecked")
    static JsonNode toJson(Map<String, Object> schema) {
        return MAPPER.valueToTree(schema == null ? Map.of() : schema);
    }

    /** Returns compact JSON text for a script-visible value. */
    static String stringify(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    /**
     * Field names of an object schema with nested object fields, as pi's {@code describeFields}.
     *
     * @param schema schema node
     * @param root root schema for local refs
     * @param depth nesting depth
     * @return field list text, or {@code null} if the schema is not an object
     */
    static String describeFields(JsonNode schema, JsonNode root, int depth) {
        JsonNode resolved = followRef(schema, root, new java.util.HashSet<>());
        if (resolved == null || !resolved.isObject()
                || !"object".equals(resolved.path("type").asText())
                || !resolved.path("properties").isObject()) {
            return null;
        }
        JsonNode properties = resolved.path("properties");
        Set<String> required = new java.util.HashSet<>();
        if (resolved.path("required").isArray()) {
            for (JsonNode item : resolved.path("required")) {
                required.add(item.asText());
            }
        }
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            JsonNode field = property.getValue();
            JsonNode target = followRef(field, root, new java.util.HashSet<>());
            JsonNode constant = target == null ? null : target.get("const");
            String nested = constant != null && !constant.isNull() ? stringify(constant)
                : depth > 0 ? describeFields(field, root, depth - 1) : null;
            fields.add(property.getKey() + (required.contains(property.getKey()) ? "" : "?")
                + (nested == null ? "" : ": " + nested));
        }
        return "{ " + String.join(", ", fields) + " }";
    }

    /** Field lists of every variant of an {@code anyOf}/{@code oneOf} schema. */
    static String describeUnionFields(JsonNode schema, JsonNode root) {
        JsonNode resolved = followRef(schema, root, new java.util.HashSet<>());
        if (resolved == null) {
            return null;
        }
        JsonNode variantsNode = resolved.get("anyOf");
        if (variantsNode == null || !variantsNode.isArray()) {
            variantsNode = resolved.get("oneOf");
        }
        if (variantsNode == null || !variantsNode.isArray() || variantsNode.isEmpty()) {
            return null;
        }
        List<String> described = new ArrayList<>();
        for (JsonNode variant : variantsNode) {
            String fields = describeFields(variant, root, 0);
            if (fields == null) {
                return null;
            }
            described.add(fields);
        }
        return String.join(" | ", described);
    }

    /** Builds a tool schema for a discovery global with an explicit signature. */
    static CodeModeSchemaTypes.ToolSchema global(String name, String description,
												 String signature) {
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("type", "object");
        return new CodeModeSchemaTypes.ToolSchema(
            name, description, input, TEXT_OUTPUT, signature);
    }

    /** Renders declaration text for discovery and model globals. */
    static String declarations(List<CodeModeSchemaTypes.ToolSchema> globals) {
        return CodeModeDeclarations.renderDeclarations(
            new CodeModeSchemaTypes.RenderOptions(List.of(), globals));
    }

    private static JsonNode followRef(JsonNode schema, JsonNode root, Set<JsonNode> seen) {
        JsonNode current = schema;
        while (current != null && current.isObject() && seen.add(current)) {
            JsonNode ref = current.get("$ref");
            if (ref == null || !ref.isTextual()) {
                break;
            }
            current = CodeModeDeclarations.resolveSchemaRef(ref.asText(), root);
        }
        return current;
    }
}
