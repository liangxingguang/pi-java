package com.pijava.codemode.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders JSON Schema documents as TypeScript type expressions. */
final class CodeModeTypeRenderer {
    private final JsonNode root;
    private final Set<String> resolving = new LinkedHashSet<>();
    private final boolean tags;
    private int expansions;

    private CodeModeTypeRenderer(JsonNode root, boolean tags) {
        this.root = root;
        this.tags = tags;
    }

    static String render(JsonNode schema, boolean tags) {
        return new CodeModeTypeRenderer(schema, tags).toType(schema);
    }

    private String toType(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return "unknown";
        }
        if (schema.isBoolean()) {
            return schema.asBoolean() ? "unknown" : "never";
        }
        if (!schema.isObject()) {
            return "unknown";
        }
        JsonNode ref = schema.get("$ref");
        if (ref != null && ref.isTextual()) {
            String refText = ref.asText();
            if (resolving.contains(refText) || expansions >= CodeModeSchemaSupport.MAX_REF_EXPANSIONS) {
                return "unknown";
            }
            JsonNode target = CodeModeSchemaSupport.resolveSchemaRef(refText, root);
            if (target == null) {
                return "unknown";
            }
            expansions++;
            resolving.add(refText);
            try {
                return toType(target);
            } finally {
                resolving.remove(refText);
            }
        }
        if (schema.has("const")) {
            return CodeModeSchemaSupport.jsonText(schema.get("const"));
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode value : enumNode) {
                values.add(CodeModeSchemaSupport.jsonText(value));
            }
            return CodeModeSchemaSupport.union(values);
        }
        JsonNode variants = variantNode(schema);
        if (variants != null) {
            List<String> types = new ArrayList<>();
            for (JsonNode variant : variants) {
                types.add(toType(variant));
            }
            return CodeModeSchemaSupport.union(types);
        }
        JsonNode allOf = schema.get("allOf");
        if (allOf != null && allOf.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : allOf) {
                String type = toType(part);
                if (!type.equals("unknown")) {
                    parts.add(type);
                }
            }
            if (parts.isEmpty()) {
                return "unknown";
            }
            List<String> wrapped = new ArrayList<>();
            for (String part : parts) {
                wrapped.add(part.contains(" | ") ? "(" + part + ")" : part);
            }
            return String.join(" & ", wrapped);
        }
        JsonNode type = schema.get("type");
        if (type != null && type.isArray()) {
            List<String> types = new ArrayList<>();
            for (JsonNode entry : type) {
                types.add(toType(withType(schema, entry)));
            }
            return CodeModeSchemaSupport.union(types);
        }
        String typeText = type != null && type.isTextual() ? type.asText() : null;
        if (typeText == null) {
            if (schema.has("properties") || schema.has("additionalProperties") || schema.has("required")) {
                return objectType(schema);
            }
            if (schema.has("items") || schema.has("prefixItems")) {
                return arrayType(schema);
            }
            return "unknown";
        }
        return switch (typeText) {
            case "string" -> "string";
            case "number", "integer" -> "number";
            case "boolean" -> "boolean";
            case "null" -> "null";
            case "array" -> arrayType(schema);
            case "object" -> objectType(schema);
            default -> "unknown";
        };
    }

    private static JsonNode variantNode(JsonNode schema) {
        JsonNode anyOf = schema.get("anyOf");
        if (anyOf != null && anyOf.isArray()) {
            return anyOf;
        }
        JsonNode oneOf = schema.get("oneOf");
        return oneOf != null && oneOf.isArray() ? oneOf : null;
    }

    private static JsonNode withType(JsonNode schema, JsonNode type) {
        JsonNode copy = schema.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).set("type", type);
        return copy;
    }

    private String arrayType(JsonNode schema) {
        JsonNode items = schema.get("items");
        if (items != null && !items.isArray()) {
            return "Array<" + toType(items) + ">";
        }
        JsonNode tuple = schema.get("prefixItems");
        if (tuple == null || !tuple.isArray()) {
            tuple = items;
        }
        if (tuple != null && tuple.isArray() && !tuple.isEmpty()) {
            List<String> types = new ArrayList<>();
            for (JsonNode item : tuple) {
                types.add(toType(item));
            }
            return "[" + String.join(", ", types) + "]";
        }
        return "unknown[]";
    }

    private String objectType(JsonNode schema) {
        JsonNode propertiesNode = schema.get("properties");
        Map<String, JsonNode> properties = new java.util.TreeMap<>();
        if (propertiesNode != null && propertiesNode.isObject()) {
            propertiesNode.properties().forEach(entry -> properties.put(entry.getKey(), entry.getValue()));
        }
        Set<String> required = new LinkedHashSet<>();
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            for (JsonNode name : requiredNode) {
                required.add(name.asText());
            }
        }
        List<String> members = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : properties.entrySet()) {
            String optional = required.contains(entry.getKey()) ? "" : "?";
            members.add(CodeModeSchemaSupport.propertyKey(entry.getKey()) + optional + ": "
                    + toType(entry.getValue()) + ";");
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && !additional.isBoolean() || additional != null && additional.asBoolean()) {
            String type = additional.isBoolean() ? "unknown" : toType(additional);
            members.add("[key: string]: " + type + ";");
        } else if (additional == null && properties.isEmpty()) {
            members.add("[key: string]: unknown;");
        }
        if (members.isEmpty()) {
            return "{}";
        }
        List<List<String>> comments = new ArrayList<>();
        boolean hasComment = false;
        for (Map.Entry<String, JsonNode> entry : properties.entrySet()) {
            List<String> lines = new ArrayList<>();
            List<String> description = CodeModeSchemaSupport.splitLines(
                    CodeModeSchemaSupport.descriptionOf(entry.getValue()));
            for (String line : description) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
            String constraintTags = tags ? CodeModeSchemaSupport.constraintTags(entry.getValue()) : "";
            if (!constraintTags.isEmpty()) {
                lines.add(constraintTags);
            }
            if (!lines.isEmpty()) {
                hasComment = true;
            }
            comments.add(lines);
        }
        if (!hasComment) {
            return "{ " + String.join(" ", members) + " }";
        }
        List<String> lines = new ArrayList<>();
        lines.add("{");
        for (int i = 0; i < comments.size(); i++) {
            for (String comment : comments.get(i)) {
                lines.add(CodeModeSchemaSupport.INDENT + "// " + comment);
            }
            lines.add(CodeModeSchemaSupport.INDENT + members.get(i).replace("\n", "\n" + CodeModeSchemaSupport.INDENT));
        }
        for (int i = properties.size(); i < members.size(); i++) {
            lines.add(CodeModeSchemaSupport.INDENT + members.get(i));
        }
        lines.add("}");
        return String.join("\n", lines);
    }
}
