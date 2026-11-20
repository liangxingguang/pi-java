package com.pijava.ai.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * pi-schema's {@code coerce: true} path: optional nulls, TypeBox conversions, then primitive coercion.
 *
 * <p>Values are copied only along changed paths; the caller's map/list is never modified.
 */
final class SchemaCoerce {

    private static final int MAX_DEPTH = 256;

    private final SchemaRefResolver resolver;
    private final SchemaCheck check;

    SchemaCoerce(SchemaRefResolver resolver) {
        this.resolver = resolver;
        this.check = new SchemaCheck(resolver);
    }

    Object coerce(Object schema, Object value) {
        Object withoutNulls = dropOptionalNulls(schema, value, 0);
        Object converted = new SchemaConvert(resolver).convert(schema, withoutNulls, 0);
        return coerceTypes(schema, converted, 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Pass 1: optional nulls
    // ---------------------------------------------------------------------------------------------

    private Object dropOptionalNulls(Object schema, Object value, int depth) {
        if (depth > MAX_DEPTH || !(schema instanceof Map<?, ?> node)) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> out = null;
            for (int i = 0; i < list.size(); i++) {
                Object itemSchema = JsonValues.itemSchemaAt(node, i);
                Object next = itemSchema == null ? list.get(i) : dropOptionalNulls(itemSchema, list.get(i), depth + 1);
                if (next != list.get(i)) {
                    if (out == null) {
                        out = new ArrayList<>(list);
                    }
                    out.set(i, next);
                }
            }
            return out == null ? value : out;
        }
        if (!(value instanceof Map<?, ?> object)) {
            return value;
        }
        Map<?, ?> properties = JsonValues.mapAt(node, "properties");
        Map<?, ?> patterns = JsonValues.mapAt(node, "patternProperties");
        Object additional = node.get("additionalProperties");
        if (patterns.isEmpty() && additional == null && properties.isEmpty()) {
            return value;
        }
        List<?> required = JsonValues.listAt(node, "required");
        Map<String, Object> out = null;
        for (var entry : properties.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!object.containsKey(key)) {
                continue;
            }
            Object property = entry.getValue();
            boolean isRef = property instanceof Map<?, ?> map && map.get("$ref") instanceof String;
            if (object.get(key) == null && !required.contains(key) && !isRef
                    && !check.check(property, null)) {
                if (out == null) {
                    out = JsonValues.copy(object);
                }
                out.remove(key);
                continue;
            }
            Object next = dropOptionalNulls(property, object.get(key), depth + 1);
            if (next != object.get(key)) {
                if (out == null) {
                    out = JsonValues.copy(object);
                }
                out.put(key, next);
            }
        }
        if (!patterns.isEmpty() || additional != null) {
            for (Object rawKey : object.keySet()) {
                String key = String.valueOf(rawKey);
                boolean matched = properties.containsKey(key);
                Object current = out != null && out.containsKey(key) ? out.get(key) : object.get(key);
                for (var patternEntry : patterns.entrySet()) {
                    if (!JsonValues.regex(String.valueOf(patternEntry.getKey())).matcher(key).find()) {
                        continue;
                    }
                    matched = true;
                    Object next = dropOptionalNulls(patternEntry.getValue(), current, depth + 1);
                    if (next != current) {
                        if (out == null) {
                            out = JsonValues.copy(object);
                        }
                        out.put(key, next);
                        current = next;
                    }
                }
                if (!matched && additional != null) {
                    Object next = dropOptionalNulls(additional, current, depth + 1);
                    if (next != current) {
                        if (out == null) {
                            out = JsonValues.copy(object);
                        }
                        out.put(key, next);
                    }
                }
            }
        }
        return out == null ? value : out;
    }

    // ---------------------------------------------------------------------------------------------
    // Pass 3: primitive coercion by type (Ajv's coerceTypes behaviour)
    // ---------------------------------------------------------------------------------------------

    private Object coerceTypes(Object schema, Object value, int depth) {
        if (depth > MAX_DEPTH || !(schema instanceof Map<?, ?> node)) {
            return value;
        }
        Object next = value;
        if (node.get("allOf") instanceof List<?> members) {
            for (Object member : members) {
                next = coerceTypes(member, next, depth + 1);
            }
        }
        if (node.get("anyOf") instanceof List<?> branches) {
            next = coerceUnion(next, branches, depth);
        }
        if (node.get("oneOf") instanceof List<?> branches) {
            next = coerceUnion(next, branches, depth);
        }
        List<String> types = typesOf(node);
        Object currentValue = next;
        boolean matchesUnionMember = types.size() > 1
                && types.stream().anyMatch(type -> SchemaCheck.matchesType(type, currentValue));
        if (!types.isEmpty() && !matchesUnionMember) {
            for (String type : types) {
                Object candidate = SchemaPrimitives.coercePrimitive(next, type);
                if (candidate != next) {
                    next = candidate;
                    break;
                }
            }
        }
        if (types.contains("object") && next instanceof Map<?, ?> object) {
            next = coerceProperties(object, node, depth);
        }
        if (types.contains("array") && next instanceof List<?> list) {
            next = coerceItems(list, node, depth);
        }
        return next;
    }

    private Object coerceUnion(Object value, List<?> branches, int depth) {
        for (Object branch : branches) {
            if (check.check(branch, value)) {
                return value;
            }
        }
        for (Object branch : branches) {
            Object coerced = coerceTypes(branch, value, depth + 1);
            if (check.check(branch, coerced)) {
                return coerced;
            }
        }
        return value;
    }

    private Object coerceProperties(Map<?, ?> object, Map<?, ?> node, int depth) {
        Map<?, ?> properties = JsonValues.mapAt(node, "properties");
        Map<?, ?> patterns = JsonValues.mapAt(node, "patternProperties");
        Object additional = node.get("additionalProperties");
        Map<String, Object> out = null;
        for (var entry : properties.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!object.containsKey(key)) {
                continue;
            }
            Object next = coerceTypes(entry.getValue(), object.get(key), depth + 1);
            if (next != object.get(key)) {
                if (out == null) {
                    out = JsonValues.copy(object);
                }
                out.put(key, next);
            }
        }
        for (Object rawKey : object.keySet()) {
            String key = String.valueOf(rawKey);
            boolean matched = properties.containsKey(key);
            Object current = out != null && out.containsKey(key) ? out.get(key) : object.get(key);
            for (var patternEntry : patterns.entrySet()) {
                if (!JsonValues.regex(String.valueOf(patternEntry.getKey())).matcher(key).find()) {
                    continue;
                }
                matched = true;
                Object next = coerceTypes(patternEntry.getValue(), current, depth + 1);
                if (next != current) {
                    if (out == null) {
                        out = JsonValues.copy(object);
                    }
                    out.put(key, next);
                    current = next;
                }
            }
            if (!matched && additional != null) {
                Object next = coerceTypes(additional, current, depth + 1);
                if (next != current) {
                    if (out == null) {
                        out = JsonValues.copy(object);
                    }
                    out.put(key, next);
                }
            }
        }
        return out == null ? object : out;
    }

    private Object coerceItems(List<?> list, Map<?, ?> node, int depth) {
        List<Object> out = null;
        for (int i = 0; i < list.size(); i++) {
            Object itemSchema = JsonValues.itemSchemaAt(node, i);
            Object next = itemSchema instanceof Map<?, ?>
                    ? coerceTypes(itemSchema, list.get(i), depth + 1) : list.get(i);
            if (next != list.get(i)) {
                if (out == null) {
                    out = new ArrayList<>(list);
                }
                out.set(i, next);
            }
        }
        return out == null ? list : out;
    }

    private static List<String> typesOf(Map<?, ?> node) {
        if (node.get("type") instanceof String type) {
            return List.of(type);
        }
        if (node.get("type") instanceof List<?> list) {
            List<String> types = new ArrayList<>();
            for (Object type : list) {
                if (type instanceof String name) {
                    types.add(name);
                }
            }
            return types;
        }
        return List.of();
    }
}
