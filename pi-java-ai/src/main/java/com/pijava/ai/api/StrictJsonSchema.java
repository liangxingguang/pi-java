package com.pijava.ai.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * pi {@code makeStrictJsonSchema} ＋ {@code makeJsonSchemaNodeStrict}
 * （{@code constrained-sampling.ts:53-127}）：把工具的 JSON Schema 转换为 provider
 * strict 子集 —— 禁用键拒绝、object 属性全部 required、非 required 属性补 null union、
 * {@code additionalProperties:false}。
 *
 * <p><b>纯函数</b>：内部深拷贝（Jackson convertValue），入参不被修改。</p>
 */
public final class StrictJsonSchema {

    /** pi {@code UNSUPPORTED_STRICT_SCHEMA_KEYS}（{@code :12-29}），逐字同序。 */
    private static final Set<String> UNSUPPORTED_KEYS = Set.of(
        "$ref", "$defs", "definitions", "allOf", "oneOf", "patternProperties",
        "dependentSchemas", "dependencies", "unevaluatedProperties", "propertyNames",
        "contains", "prefixItems", "not", "if", "then", "else");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private StrictJsonSchema() {}

    /**
     * Convert a tool schema to the strict subset.
     *
     * @throws UnsupportedStrictJsonSchemaException schema 不可转换
     */
    public static Map<String, Object> convert(Map<String, Object> schema) {
        var cloned = MAPPER.convertValue(schema, MAP_TYPE);
        makeNodeStrict(cloned);
        if (!"object".equals(cloned.get("type"))) {
            throw new UnsupportedStrictJsonSchemaException("root schema must have type object");
        }
        return cloned;
    }

    /** pi {@code makeJsonSchemaNodeStrict}（{@code :53-114}），原地改 node。 */
    private static void makeNodeStrict(Map<String, Object> node) {
        for (var key : UNSUPPORTED_KEYS) {
            if (node.containsKey(key)) {
                throw new UnsupportedStrictJsonSchemaException(key + " schemas are unsupported");
            }
        }

        if (node.containsKey("anyOf")) {
            var raw = node.get("anyOf");
            if (!(raw instanceof List<?> variants) || variants.isEmpty()) {
                throw new UnsupportedStrictJsonSchemaException(
                    "anyOf must contain at least one schema");
            }
            for (var variant : variants) {
                var variantNode = asNode(variant);
                if (isStructuredSchema(variantNode)) {
                    throw new UnsupportedStrictJsonSchemaException(
                        "object and array unions are unsupported");
                }
                makeNodeStrict(variantNode);
            }
        }

        if (node.containsKey("items")) {
            var raw = node.get("items");
            if (raw instanceof List<?>) {
                throw new UnsupportedStrictJsonSchemaException("tuple schemas are unsupported");
            }
            makeNodeStrict(asNode(raw));
        }

        var isObject = "object".equals(node.get("type"));
        if (node.containsKey("properties") && !isObject) {
            throw new UnsupportedStrictJsonSchemaException("properties require type object");
        }
        if (!isObject) {
            return;
        }

        if (node.containsKey("additionalProperties") && node.get("additionalProperties") != Boolean.FALSE) {
            throw new UnsupportedStrictJsonSchemaException(
                "schema-valued or true additionalProperties is unsupported");
        }
        if (node.containsKey("properties") && !(node.get("properties") instanceof Map<?, ?>)) {
            throw new UnsupportedStrictJsonSchemaException(
                "object properties must be a schema map");
        }
        if (node.containsKey("required") && !isStringList(node.get("required"))) {
            throw new UnsupportedStrictJsonSchemaException(
                "object required must be a string array");
        }

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) node.getOrDefault("properties", Map.of());
        var propertyNames = new ArrayList<String>(properties.size());
        properties.forEach((key, value) -> {
            if (!(value instanceof Map<?, ?>)) {
                throw new UnsupportedStrictJsonSchemaException("boolean schemas are unsupported");
            }
            propertyNames.add(key);
        });
        var required = new java.util.LinkedHashSet<String>();
        if (node.get("required") instanceof List<?> requiredList) {
            requiredList.forEach(key -> required.add((String) key));
        }
        if (required.stream().anyMatch(key -> !propertyNames.contains(key))) {
            throw new UnsupportedStrictJsonSchemaException(
                "required contains an unknown property");
        }

        for (var key : propertyNames) {
            var property = asNode(properties.get(key));
            makeNodeStrict(property);
            if (!required.contains(key) && !schemaAllowsNull(property)) {
                properties.put(key, Map.of("anyOf", List.of(property, Map.of("type", "null"))));
            }
        }
        node.put("required", propertyNames);
        node.put("additionalProperties", false);
    }

    /** pi {@code isStructuredSchema}（{@code :35-44}）。 */
    private static boolean isStructuredSchema(Map<String, Object> schema) {
        var rawType = schema.get("type");
        var types = rawType instanceof String s ? List.of(s)
            : rawType instanceof List<?> list ? list : List.of();
        return types.contains("object") || types.contains("array")
            || schema.get("properties") != null || schema.get("items") != null;
    }

    /** pi {@code schemaAllowsNull}（{@code :46-51}）。 */
    private static boolean schemaAllowsNull(Map<String, Object> schema) {
        var rawType = schema.get("type");
        if ("null".equals(rawType)
                || (rawType instanceof List<?> list && list.contains("null"))) {
            return true;
        }
        if ((schema.containsKey("const") && schema.get("const") == null)
                || (schema.get("enum") instanceof List<?> values && values.contains(null))) {
            return true;
        }
        return schema.get("anyOf") instanceof List<?> anyOf
            && anyOf.stream().anyMatch(variant -> schemaAllowsNull(asNode(variant)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asNode(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new UnsupportedStrictJsonSchemaException("boolean schemas are unsupported");
        }
        return (Map<String, Object>) value;
    }

    private static boolean isStringList(Object value) {
        return value instanceof List<?> list && !list.isEmpty()
            && list.stream().allMatch(key -> key instanceof String);
    }
}
