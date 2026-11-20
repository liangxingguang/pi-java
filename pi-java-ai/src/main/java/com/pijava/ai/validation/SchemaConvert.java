package com.pijava.ai.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** pi-schema's pass 2: TypeBox's {@code Value.Convert} rules. */
final class SchemaConvert {

    private static final int MAX_DEPTH = 256;

    private final SchemaRefResolver resolver;
    private final SchemaCheck check;

    SchemaConvert(SchemaRefResolver resolver) {
        this.resolver = resolver;
        this.check = new SchemaCheck(resolver);
    }

    Object convert(Object schema, Object value, int depth) {
        if (depth > MAX_DEPTH || !(schema instanceof Map<?, ?> node)) {
            return value;
        }
        if (node.get("$ref") instanceof String ref) {
            var resolved = resolver.resolve(ref);
            return resolved.unresolved() ? value : convert(resolved.node(), value, depth + 1);
        }
        if (node.containsKey("const")) {
            return SchemaPrimitives.convertLiteral(node.get("const"), value);
        }
        if (node.get("enum") instanceof List<?> values) {
            return convertEnum(node, values, value);
        }
        if (node.get("anyOf") instanceof List<?> branches) {
            return convertUnion(node, branches, value, depth);
        }
        if (node.get("oneOf") instanceof List<?> branches) {
            return convertUnion(node, branches, value, depth);
        }
        if (node.get("allOf") instanceof List<?> members) {
            Object next = value;
            for (Object member : members) {
                next = convert(member, next, depth + 1);
            }
            return convertMembers(node, next, depth);
        }
        if (node.get("type") instanceof String type) {
            return convertType(node, type, value, depth);
        }
        if (node.get("properties") != null || node.get("patternProperties") != null) {
            return convertObject(node, value, depth);
        }
        if (node.get("items") != null || node.get("prefixItems") != null) {
            return convertArray(node, value, depth);
        }
        return value;
    }

    private Object convertType(Map<?, ?> node, String type, Object value, int depth) {
        return switch (type) {
            case "string" -> SchemaPrimitives.orValue(SchemaPrimitives.tryString(value), value);
            case "number" -> SchemaPrimitives.orValue(SchemaPrimitives.tryNumber(value), value);
            case "integer" -> {
                Object number = SchemaPrimitives.tryNumber(value);
                yield number == SchemaPrimitives.NOT_CONVERTED ? value : SchemaPrimitives.truncate(number);
            }
            case "boolean" -> SchemaPrimitives.orValue(SchemaPrimitives.tryBoolean(value), value);
            case "null" -> SchemaPrimitives.orValue(SchemaPrimitives.tryNull(value), value);
            case "array" -> convertArray(node, value, depth);
            case "object" -> convertObject(node, value, depth);
            default -> value;
        };
    }

    private Object convertEnum(Map<?, ?> node, List<?> values, Object value) {
        if (values.stream().anyMatch(v -> v == value || JsonValues.equal(v, value))) {
            return value;
        }
        for (Object member : values) {
            Object candidate = SchemaPrimitives.convertLiteral(member, value);
            if (candidate != value && check.check(node, candidate)) {
                return candidate;
            }
        }
        return value;
    }

    private Object convertUnion(Map<?, ?> node, List<?> branches, Object value, int depth) {
        if (branches.stream().anyMatch(branch -> check.check(branch, value))) {
            return convertMembers(node, value, depth);
        }
        for (Object branch : branches) {
            Object candidate = convertMembers(node, convert(branch, value, depth + 1), depth);
            if (check.check(node, candidate)) {
                return candidate;
            }
        }
        return convertMembers(node, value, depth);
    }

    private Object convertMembers(Map<?, ?> node, Object value, int depth) {
        boolean declares = node.get("properties") != null || node.get("patternProperties") != null
                || node.get("additionalProperties") != null;
        return declares ? convertObject(node, value, depth) : value;
    }

    private Object convertArray(Map<?, ?> node, Object value, int depth) {
        List<?> prefix = JsonValues.tuplePrefix(node);
        if (prefix != null) {
            if (!(value instanceof List<?> list)) {
                return value; // A tuple converts items but never wraps a single value.
            }
            Object rest = node.get("items") instanceof List<?> ? null : node.get("items");
            List<Object> out = null;
            for (int i = 0; i < list.size(); i++) {
                Object schema = i < prefix.size() ? prefix.get(i) : rest;
                Object next = schema == null ? list.get(i) : convert(schema, list.get(i), depth + 1);
                if (next != list.get(i)) {
                    if (out == null) {
                        out = new ArrayList<>(list);
                    }
                    out.set(i, next);
                }
            }
            return out == null ? value : out;
        }
        if (value instanceof List<?> list) {
            Object items = node.get("items");
            if (items == null) {
                return value;
            }
            List<Object> out = null;
            for (int i = 0; i < list.size(); i++) {
                Object next = convert(items, list.get(i), depth + 1);
                if (next != list.get(i)) {
                    if (out == null) {
                        out = new ArrayList<>(list);
                    }
                    out.set(i, next);
                }
            }
            return out == null ? value : out;
        }
        Object items = node.get("items");
        List<Object> wrapped = new ArrayList<>(1);
        wrapped.add(items == null ? value : convert(items, value, depth + 1));
        return wrapped;
    }

    private Object convertObject(Map<?, ?> node, Object value, int depth) {
        if (!(value instanceof Map<?, ?> object)) {
            return value;
        }
        Map<?, ?> properties = JsonValues.mapAt(node, "properties");
        Map<?, ?> patterns = JsonValues.mapAt(node, "patternProperties");
        Object additional = node.get("additionalProperties");
        Map<String, Object> out = null;
        for (var entry : object.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object next = entry.getValue();
            boolean matched = properties.containsKey(key);
            if (matched) {
                next = convert(properties.get(key), next, depth + 1);
            }
            for (var patternEntry : patterns.entrySet()) {
                if (!JsonValues.regex(String.valueOf(patternEntry.getKey())).matcher(key).find()) {
                    continue;
                }
                matched = true;
                next = convert(patternEntry.getValue(), next, depth + 1);
            }
            if (!matched && additional != null) {
                next = convert(additional, next, depth + 1);
            }
            if (next != entry.getValue()) {
                if (out == null) {
                    out = JsonValues.copy(object);
                }
                out.put(key, next);
            }
        }
        return out == null ? value : out;
    }
}
