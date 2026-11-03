package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * #111（docs/17 §7）：Gemini JSON-Schema 的 {@code const} → 单值 {@code enum} 改写，
 * 逐字对位 pi {@code google-shared.ts:390-430}。
 *
 * <p>Gemini 不接受 {@code const}，只接受 {@code enum}。改写只针对 {@code string} /
 * {@code number} 的 {@code const}（boolean / null / object 原样保留），且已有 {@code enum}
 * 时不动。{@code properties} 一类「名字映射」下的键是属性名、不是 schema，不得递归改写。</p>
 */
final class GoogleJsonSchema {

    private GoogleJsonSchema() {}

    /** pi {@code JSON_SCHEMA_NAME_MAPS}：值是「名字 → schema」的映射。 */
    private static final Set<String> NAME_MAPS = Set.of(
        "properties", "patternProperties", "$defs", "definitions", "dependentSchemas",
        "dependencies");

    /** pi {@code JSON_SCHEMA_SUBSCHEMAS}：值是 schema（或其数组）。 */
    private static final Set<String> SUBSCHEMAS = Set.of(
        "additionalProperties", "unevaluatedProperties", "propertyNames", "items",
        "prefixItems", "additionalItems", "unevaluatedItems", "contains", "allOf", "anyOf",
        "oneOf", "not", "if", "then", "else", "contentSchema");

    /**
     * pi {@code rewriteConstAsEnum}：递归改写；{@code isNameMap} 为真时当前节点的键是
     * 属性名，键对应的值仍是 schema，但本节点自身不是 schema（不做 const 改写）。
     */
    static Object rewriteConstAsEnum(Object schema) {
        return rewrite(schema, false);
    }

    private static Object rewrite(Object schema, boolean isNameMap) {
        if (schema instanceof List<?> list) {
            // pi 的数组分支不带 isNameMap —— 数组项一律按 schema 处理。
            return list.stream().map(item -> rewrite(item, false)).toList();
        }
        if (!(schema instanceof Map<?, ?> map)) {
            return schema;
        }
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (isNameMap || SUBSCHEMAS.contains(key)) {
                result.put(key, rewrite(value, false));
            } else if (NAME_MAPS.contains(key)) {
                result.put(key, rewrite(value, true));
            } else {
                result.put(key, value);
            }
        }
        if (!isNameMap && result.containsKey("const") && !result.containsKey("enum")) {
            Object value = result.get("const");
            if (value instanceof String || value instanceof Number) {
                result.remove("const");
                result.put("enum", List.of(value));
            }
        }
        return result;
    }
}
