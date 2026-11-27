package com.pijava.codemode.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Schema helpers shared by declaration and type rendering. */
final class CodeModeSchemaSupport {
    static final String INDENT = "  ";
    static final int DEFAULT_INPUT_SCHEMA_MAX_CHARS = 16_000;
    static final int MAX_REF_EXPANSIONS = 32;
    static final int MAX_TAG_CHARS = 80;
    static final List<String> TAG_KEYWORDS = List.of(
            "minimum", "exclusiveMinimum", "maximum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength",
            "pattern", "format", "minItems", "maxItems");
    static final Set<String> LOWER_BOUNDS = Set.of("minimum", "exclusiveMinimum", "minLength", "minItems");
    static final Set<String> UPPER_BOUNDS = Set.of("maximum", "exclusiveMaximum", "maxLength", "maxItems");

    private CodeModeSchemaSupport() {
    }

    static String propertyKey(String name) {
        return isIdentifier(name) ? name : jsonText(JsonNodeFactory.instance.textNode(name));
    }

    static String jsonText(JsonNode node) {
        return node.toString();
    }

    static boolean isIdentifier(String name) {
        if (name.isEmpty() || !(isLetter(name.charAt(0)) || name.charAt(0) == '_' || name.charAt(0) == '$')) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char character = name.charAt(i);
            if (!(isLetter(character) || isDigit(character) || character == '_' || character == '$')) {
                return false;
            }
        }
        return true;
    }

    static boolean isLetter(char character) {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }

    static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    static String union(List<String> types) {
        Set<String> unique = new LinkedHashSet<>(types);
        if (unique.contains("unknown")) {
            return "unknown";
        }
        return unique.isEmpty() ? "never" : String.join(" | ", unique);
    }

    static JsonNode resolveSchemaRef(String ref, JsonNode root) {
        if (!ref.equals("#") && !ref.startsWith("#/")) {
            return null;
        }
        JsonNode current = root;
        String pointer = ref.equals("#") ? "" : ref.substring(2);
        for (String segment : pointer.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            String decoded;
            try {
                decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return null;
            }
            String key = decoded.replace("~1", "/").replace("~0", "~");
            if (!current.isObject() || !current.has(key)) {
                return null;
            }
            current = current.get(key);
        }
        if (current.isBoolean() || current.isObject() && !current.isArray()) {
            return current;
        }
        return null;
    }

    static String descriptionOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode description = node.get("description");
        return description != null && description.isTextual() ? description.asText().trim() : "";
    }

    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\r' || character == '\n' || character == '\u2028' || character == '\u2029') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(text.substring(start));
        return lines;
    }

    static String constraintTags(JsonNode property) {
        if (property == null || !property.isObject()) {
            return "";
        }
        List<JsonNode> branches = new ArrayList<>();
        branches.add(property);
        JsonNode variants = property.has("anyOf") ? property.get("anyOf") : property.get("oneOf");
        if (variants != null && variants.isArray()) {
            List<JsonNode> nonNull = new ArrayList<>();
            for (JsonNode variant : variants) {
                JsonNode type = variant.get("type");
                if (type == null || !type.asText().equals("null")) {
                    nonNull.add(variant);
                }
            }
            if (nonNull.size() == 1) {
                branches.add(nonNull.get(0));
            }
        }
        List<Map<String, JsonNode>> schemas = new ArrayList<>();
        for (JsonNode branch : branches) {
            Map<String, JsonNode> adjusted = new LinkedHashMap<>();
            branch.properties().forEach(entry -> adjusted.put(entry.getKey(), entry.getValue()));
            if (adjusted.get("exclusiveMinimum") != null && adjusted.get("exclusiveMinimum").asBoolean(false)) {
                adjusted.remove("minimum");
                adjusted.put("exclusiveMinimum", branch.get("minimum"));
            }
            if (adjusted.get("exclusiveMaximum") != null && adjusted.get("exclusiveMaximum").asBoolean(false)) {
                adjusted.remove("maximum");
                adjusted.put("exclusiveMaximum", branch.get("maximum"));
            }
            schemas.add(adjusted);
        }
        List<String> tags = new ArrayList<>();
        if (schemas.stream().anyMatch(schema -> isIntegerType(schema.get("type")))) {
            tags.add("@integer");
        }
        for (String keyword : TAG_KEYWORDS) {
            List<JsonNode> values = schemas.stream().map(schema -> schema.get(keyword))
                    .filter(Objects::nonNull).toList();
            if (LOWER_BOUNDS.contains(keyword) || UPPER_BOUNDS.contains(keyword)) {
                List<Double> numbers = values.stream().filter(JsonNode::isNumber).map(JsonNode::asDouble).toList();
                if (numbers.isEmpty()) {
                    continue;
                }
                double bound = LOWER_BOUNDS.contains(keyword)
                        ? numbers.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                        : numbers.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                tags.add("@" + keyword + " " + numberText(bound));
                continue;
            }
            Set<String> seen = new LinkedHashSet<>();
            Iterator<JsonNode> iterator = values.iterator();
            while (iterator.hasNext()) {
                JsonNode value = iterator.next();
                if (value.isNumber() || value.isTextual()) {
                    seen.add(value.asText());
                }
            }
            seen.forEach(value -> tags.add("@" + keyword + " " + value));
        }
        if (schemas.stream().anyMatch(schema -> schema.get("uniqueItems") != null
                && schema.get("uniqueItems").asBoolean(false))) {
            tags.add("@uniqueItems");
        }
        JsonNode defaultValue = property.get("default");
        if (defaultValue != null && !defaultValue.isNull()) {
            tags.add("@default " + jsonText(defaultValue));
        }
        if (property.get("deprecated") != null && property.get("deprecated").asBoolean(false)) {
            tags.add("@deprecated");
        }
        List<String> kept = new ArrayList<>();
        for (String tag : tags) {
            if (tag.length() <= MAX_TAG_CHARS) {
                kept.add(escapeTag(tag));
            }
        }
        return String.join(" ", kept);
    }

    private static boolean isIntegerType(JsonNode type) {
        if (type == null) {
            return false;
        }
        if (type.isTextual()) {
            return type.asText().equals("integer");
        }
        if (type.isArray()) {
            for (JsonNode entry : type) {
                if (entry.asText().equals("integer")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String numberText(double number) {
        if (number == Math.rint(number) && !Double.isInfinite(number)) {
            return Long.toString((long) number);
        }
        return Double.toString(number);
    }

    private static String escapeTag(String tag) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < tag.length(); i++) {
            char character = tag.charAt(i);
            switch (character) {
                case '\r', '\n', '\u2028', '\u2029' ->
                        escaped.append("\\u").append(String.format("%04x", (int) character));
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
