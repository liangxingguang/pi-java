package com.pijava.ai.validation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explains a failed value keyword by keyword, using pi-schema's messages and JSON Pointer paths.
 *
 * <p>Mirrors {@code packages/schema/src/explain.ts} for the keywords in the design's supported set.
 */
final class SchemaErrors {

    /** One failure at a JSON Pointer path. */
    record Error(String path, String keyword, String message) {}

    private static final int LISTED_VALUES = 20;
    private static final int MAX_DEPTH = 256;

    private final SchemaRefResolver resolver;
    private final SchemaCheck check;

    SchemaErrors(SchemaRefResolver resolver) {
        this.resolver = resolver;
        this.check = new SchemaCheck(resolver);
    }

    List<Error> errors(Object schema, Object value) {
        if (check.check(schema, value)) {
            return List.of();
        }
        List<Error> out = new ArrayList<>();
        walk(schema, value, "", out, 0);
        if (out.isEmpty()) {
            out.add(new Error("", "schema", "invalid"));
        }
        return out;
    }

    private void walk(Object schema, Object value, String path, List<Error> out, int depth) {
        if (depth > MAX_DEPTH || schema == null || Boolean.TRUE.equals(schema)) {
            return;
        }
        if (Boolean.FALSE.equals(schema)) {
            out.add(new Error(path, "false", "no value is allowed here"));
            return;
        }
        if (!(schema instanceof Map<?, ?> node)) {
            return;
        }
        if (check.check(node, value)) {
            return;
        }
        if (node.get("type") != null) {
            List<?> types = node.get("type") instanceof List<?> list ? list : List.of(node.get("type"));
            boolean matched = types.stream().anyMatch(t -> t instanceof String s
                    && SchemaCheck.matchesType(s, value));
            if (!matched) {
                out.add(new Error(path, "type",
                        "expected " + joinTypes(types) + ", got " + JsonValues.typeName(value)));
                return;
            }
        }
        if (node.get("enum") instanceof List<?> values && !containsValue(values, value)) {
            out.add(new Error(path, "enum", "expected one of " + listValues(values)));
        }
        if (node.containsKey("const") && !JsonValues.equal(node.get("const"), value)) {
            out.add(new Error(path, "const", "expected " + JsJson.compact(node.get("const"))));
        }
        if (value instanceof String text) {
            walkString(node, text, path, out);
        }
        if (value instanceof Number number) {
            walkNumber(node, number, path, out);
        }
        if (value instanceof Map<?, ?> object) {
            walkObject(node, object, path, out, depth);
        }
        if (value instanceof List<?> array) {
            walkArray(node, array, path, out, depth);
        }
        if (node.get("allOf") instanceof List<?> members) {
            for (Object member : members) {
                walk(member, value, path, out, depth + 1);
            }
        }
        if (node.get("anyOf") instanceof List<?> branches) {
            walkUnion(branches, value, path, out, depth, false);
        }
        if (node.get("oneOf") instanceof List<?> branches) {
            walkUnion(branches, value, path, out, depth, true);
        }
        if (node.containsKey("not") && check.check(node.get("not"), value)) {
            out.add(new Error(path, "not", "must not match the schema in not"));
        }
        if (node.containsKey("if")) {
            Object branch = check.check(node.get("if"), value) ? node.get("then") : node.get("else");
            if (branch != null) {
                walk(branch, value, path, out, depth + 1);
            }
        }
        if (node.get("$ref") instanceof String ref) {
            walkRef(ref, value, path, out, depth);
        }
    }

    private void walkRef(String ref, Object value, String path, List<Error> out, int depth) {
        var resolved = resolver.resolve(ref);
        if (resolved.unresolved()) {
            out.add(new Error(path, "$ref", "unresolved reference " + ref));
            return;
        }
        walk(resolved.node(), value, path, out, depth + 1);
    }

    private void walkString(Map<?, ?> node, String value, String path, List<Error> out) {
        int length = value.codePointCount(0, value.length());
        if (node.get("minLength") instanceof Number min && length < min.intValue()) {
            out.add(new Error(path, "minLength", "expected at least " + min.intValue() + " characters"));
        }
        if (node.get("maxLength") instanceof Number max && length > max.intValue()) {
            out.add(new Error(path, "maxLength", "expected at most " + max.intValue() + " characters"));
        }
        if (node.get("pattern") instanceof String pattern && !compiled(pattern).matcher(value).find()) {
            out.add(new Error(path, "pattern", "expected to match " + pattern));
        }
        if (node.get("format") instanceof String format && !SchemaFormats.matches(format, value)) {
            out.add(new Error(path, "format", "expected format " + format));
        }
    }

    private void walkNumber(Map<?, ?> node, Number number, String path, List<Error> out) {
        BigDecimal value = JsonValues.decimal(number);
        if (node.get("minimum") instanceof Number min && value.compareTo(JsonValues.decimal(min)) < 0) {
            out.add(new Error(path, "minimum", "expected >= " + JsJson.compact(min)));
        }
        if (node.get("maximum") instanceof Number max && value.compareTo(JsonValues.decimal(max)) > 0) {
            out.add(new Error(path, "maximum", "expected <= " + JsJson.compact(max)));
        }
        if (node.get("exclusiveMinimum") instanceof Number min && value.compareTo(JsonValues.decimal(min)) <= 0) {
            out.add(new Error(path, "exclusiveMinimum", "expected > " + JsJson.compact(min)));
        }
        if (node.get("exclusiveMaximum") instanceof Number max && value.compareTo(JsonValues.decimal(max)) >= 0) {
            out.add(new Error(path, "exclusiveMaximum", "expected < " + JsJson.compact(max)));
        }
        if (node.get("multipleOf") instanceof Number divisor) {
            BigDecimal d = JsonValues.decimal(divisor);
            if (d.signum() == 0 || value.remainder(d).compareTo(BigDecimal.ZERO) != 0) {
                out.add(new Error(path, "multipleOf", "expected a multiple of " + JsJson.compact(divisor)));
            }
        }
    }

    private void walkObject(Map<?, ?> node, Map<?, ?> value, String path, List<Error> out, int depth) {
        Map<?, ?> properties = node.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        if (node.get("required") instanceof List<?> required) {
            for (Object key : required) {
                if (key instanceof String name && !value.containsKey(name)) {
                    out.add(new Error(pointer(path, name), "required", "missing"));
                }
            }
        }
        for (var entry : properties.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (value.containsKey(key)) {
                walk(entry.getValue(), value.get(key), pointer(path, key), out, depth + 1);
            }
        }
        Map<?, ?> patterns = node.get("patternProperties") instanceof Map<?, ?> p ? p : Map.of();
        for (var entry : value.entrySet()) {
            String key = String.valueOf(entry.getKey());
            boolean matched = properties.containsKey(key);
            for (var patternEntry : patterns.entrySet()) {
                if (compiled(String.valueOf(patternEntry.getKey())).matcher(key).find()) {
                    matched = true;
                    walk(patternEntry.getValue(), entry.getValue(), pointer(path, key), out, depth + 1);
                }
            }
            if (!matched && node.get("additionalProperties") != null) {
                if (Boolean.FALSE.equals(node.get("additionalProperties"))) {
                    out.add(new Error(pointer(path, key), "additionalProperties", "unknown field"));
                } else {
                    walk(node.get("additionalProperties"), entry.getValue(), pointer(path, key), out, depth + 1);
                }
            }
            if (node.get("propertyNames") != null && !check.check(node.get("propertyNames"), key)) {
                out.add(new Error(pointer(path, key), "propertyNames", "invalid property name"));
            }
        }
        if (node.get("minProperties") instanceof Number min && value.size() < min.intValue()) {
            out.add(new Error(path, "minProperties",
                    "expected at least " + min.intValue() + " properties"));
        }
        if (node.get("maxProperties") instanceof Number max && value.size() > max.intValue()) {
            out.add(new Error(path, "maxProperties",
                    "expected at most " + max.intValue() + " properties"));
        }
        walkDependencies(node, value, path, out, depth);
    }

    private void walkDependencies(Map<?, ?> node, Map<?, ?> value, String path, List<Error> out, int depth) {
        if (node.get("dependentRequired") instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!value.containsKey(String.valueOf(entry.getKey())) || !(entry.getValue() instanceof List<?> names)) {
                    continue;
                }
                for (Object key : names) {
                    if (key instanceof String name && !value.containsKey(name)) {
                        out.add(new Error(pointer(path, name), "dependentRequired",
                                "required when " + entry.getKey() + " is present"));
                    }
                }
            }
        }
        if (node.get("dependencies") instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!value.containsKey(String.valueOf(entry.getKey()))) {
                    continue;
                }
                Object dependency = entry.getValue();
                if (dependency instanceof List<?> names) {
                    for (Object key : names) {
                        if (key instanceof String name && !value.containsKey(name)) {
                            out.add(new Error(pointer(path, name), "dependentRequired",
                                    "required when " + entry.getKey() + " is present"));
                        }
                    }
                } else {
                    walk(dependency, value, path, out, depth + 1);
                }
            }
        }
        if (node.get("dependentSchemas") instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (value.containsKey(String.valueOf(entry.getKey()))) {
                    walk(entry.getValue(), value, path, out, depth + 1);
                }
            }
        }
    }

    private void walkArray(Map<?, ?> node, List<?> value, String path, List<Error> out, int depth) {
        List<Object> prefix;
        Object rest;
        if (node.get("items") instanceof List<?> tuple) {
            prefix = List.copyOf(tuple);
            rest = node.get("additionalItems");
        } else {
            prefix = node.get("prefixItems") instanceof List<?> items ? List.copyOf(items) : List.of();
            rest = node.get("items");
        }
        for (int i = 0; i < value.size(); i++) {
            Object schema = i < prefix.size() ? prefix.get(i) : rest;
            if (schema != null) {
                walk(schema, value.get(i), path + "/" + i, out, depth + 1);
            }
        }
        if (node.get("minItems") instanceof Number min && value.size() < min.intValue()) {
            out.add(new Error(path, "minItems", "expected at least " + min.intValue() + " items"));
        }
        if (node.get("maxItems") instanceof Number max && value.size() > max.intValue()) {
            out.add(new Error(path, "maxItems", "expected at most " + max.intValue() + " items"));
        }
        if (Boolean.TRUE.equals(node.get("uniqueItems")) && !isUnique(value)) {
            out.add(new Error(path, "uniqueItems", "expected unique items"));
        }
        if (node.containsKey("contains")) {
            int min = node.get("minContains") instanceof Number n ? n.intValue() : 1;
            int max = node.get("maxContains") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
            int matches = 0;
            for (Object item : value) {
                if (check.check(node.get("contains"), item)) {
                    matches++;
                }
            }
            if (matches < min || matches > max) {
                out.add(new Error(path, "contains", containsMessage(min, max)));
            }
        }
    }

    private void walkUnion(List<?> branches, Object value, String path, List<Error> out,
                           int depth, boolean exactlyOne) {
        int matches = 0;
        for (Object branch : branches) {
            if (check.check(branch, value)) {
                matches++;
            }
        }
        if (matches == 1 || (!exactlyOne && matches > 0)) {
            return;
        }
        String keyword = exactlyOne ? "oneOf" : "anyOf";
        if (matches > 1) {
            out.add(new Error(path, keyword, "matches more than one alternative"));
            return;
        }
        Tag tag = tagOf(branches);
        if (tag != null && value instanceof Map<?, ?> object) {
            int index = indexOf(tag.values, object.get(tag.key));
            if (index < 0) {
                out.add(new Error(pointer(path, tag.key), keyword, "expected one of " + listValues(tag.values)));
            } else {
                walk(branches.get(index), value, path, out, depth + 1);
            }
            return;
        }
        List<Error> best = null;
        int bestDeclared = -1;
        for (Object branch : branches) {
            List<Error> errors = new ArrayList<>();
            walk(branch, value, path, errors, depth + 1);
            if (errors.isEmpty()) {
                continue;
            }
            int declared = value instanceof Map<?, ?> object ? declaredKeys(branch, object) : 0;
            boolean better = best == null || declared > bestDeclared
                    || (declared == bestDeclared && errors.get(0).path().length()
                    > best.get(0).path().length());
            if (better) {
                best = errors;
                bestDeclared = declared;
            }
        }
        if (best == null) {
            out.add(new Error(path, keyword, "matches no alternative"));
        } else {
            out.addAll(best);
        }
    }

    private int declaredKeys(Object branch, Map<?, ?> value) {
        Object node = branch;
        if (branch instanceof Map<?, ?> map && map.get("$ref") instanceof String ref) {
            var resolved = resolver.resolve(ref);
            if (!resolved.unresolved()) {
                node = resolved.node();
            }
        }
        Map<?, ?> properties = node instanceof Map<?, ?> map && map.get("properties") instanceof Map<?, ?> p
                ? p : Map.of();
        int count = 0;
        for (Object key : value.keySet()) {
            if (properties.containsKey(String.valueOf(key))) {
                count++;
            }
        }
        return count;
    }

    private Tag tagOf(List<?> branches) {
        List<Object> resolved = new ArrayList<>();
        for (Object branch : branches) {
            Object node = branch;
            if (branch instanceof Map<?, ?> map && map.get("$ref") instanceof String ref) {
                var target = resolver.resolve(ref);
                if (!target.unresolved()) {
                    node = target.node();
                }
            }
            resolved.add(node);
        }
        if (!(resolved.get(0) instanceof Map<?, ?> first)
                || !(first.get("properties") instanceof Map<?, ?> firstProperties)) {
            return null;
        }
        for (Object key : firstProperties.keySet()) {
            List<Object> values = new ArrayList<>();
            for (Object node : resolved) {
                Object property = node instanceof Map<?, ?> map
                        && map.get("properties") instanceof Map<?, ?> properties
                        ? properties.get(key) : null;
                Object constant = property instanceof Map<?, ?> map ? map.get("const") : null;
                values.add(constant);
            }
            boolean allPrimitive = values.stream().allMatch(v -> v != null
                    && !(v instanceof Map<?, ?>) && !(v instanceof List<?>));
            Set<Object> distinct = new LinkedHashSet<>(values);
            if (allPrimitive && distinct.size() == values.size()) {
                return new Tag(String.valueOf(key), values);
            }
        }
        return null;
    }

    private record Tag(String key, List<Object> values) {}

    private static int indexOf(List<Object> values, Object value) {
        for (int i = 0; i < values.size(); i++) {
            if (JsonValues.equal(values.get(i), value)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsValue(List<?> values, Object value) {
        return values.stream().anyMatch(v -> JsonValues.equal(v, value));
    }

    private static boolean isUnique(List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            for (int j = i + 1; j < values.size(); j++) {
                if (JsonValues.equal(values.get(i), values.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String joinTypes(List<?> types) {
        return String.join(" or ", types.stream().map(String::valueOf).toList());
    }

    private static String listValues(List<?> values) {
        if (values.size() <= LISTED_VALUES) {
            return JsJson.compact(values);
        }
        String listed = JsJson.compact(values.subList(0, LISTED_VALUES));
        return listed.substring(0, listed.length() - 1) + ", ... and "
                + (values.size() - LISTED_VALUES) + " more]";
    }

    private static String containsMessage(int min, int max) {
        if (max == Integer.MAX_VALUE) {
            return "expected at least " + min + " matching " + (min == 1 ? "item" : "items");
        }
        if (min == 0) {
            return "expected at most " + max + " matching " + (max == 1 ? "item" : "items");
        }
        return "expected between " + min + " and " + max + " matching items";
    }

    private static String pointer(String path, String key) {
        return path + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    private static java.util.regex.Pattern compiled(String source) {
        try {
            return java.util.regex.Pattern.compile(source);
        } catch (RuntimeException e) {
            return java.util.regex.Pattern.compile("(?!)");
        }
    }
}
