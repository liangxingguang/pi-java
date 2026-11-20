package com.pijava.ai.validation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Boolean JSON Schema check over the keywords tool schemas use.
 *
 * <p>Mirrors pi-schema's default draft 2020-12 semantics: {@code $ref} siblings apply, a dangling
 * reference rejects at its place, and format annotations are checked when a format name is known.
 */
final class SchemaCheck {

    private static final int MAX_DEPTH = 256;

    private final SchemaRefResolver resolver;

    SchemaCheck(SchemaRefResolver resolver) {
        this.resolver = resolver;
    }

    boolean check(Object schema, Object value) {
        return check(schema, value, 0);
    }

    private boolean check(Object schema, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return false; // Cyclic schema without a value descent: reject rather than overflow.
        }
        if (schema == null || Boolean.TRUE.equals(schema)) {
            return true;
        }
        if (Boolean.FALSE.equals(schema)) {
            return false;
        }
        if (!(schema instanceof Map<?, ?> node)) {
            return true;
        }
        if (node.get("$ref") instanceof String ref) {
            var resolved = resolver.resolve(ref);
            if (resolved.unresolved()) {
                return false;
            }
            if (!check(resolved.node(), value, depth + 1)) {
                return false;
            }
            // 2020-12: keywords next to $ref still apply; fall through to the sibling checks.
            Object withoutRef = node;
            if (node.size() > 1) {
                var copy = new java.util.LinkedHashMap<String, Object>();
                for (var entry : node.entrySet()) {
                    if (!"$ref".equals(entry.getKey())) {
                        copy.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                withoutRef = copy;
            } else {
                return true;
            }
            node = (Map<?, ?>) withoutRef;
        }

        if (!checkType(node.get("type"), value)) {
            return false;
        }
        if (node.containsKey("const") && !JsonValues.equal(node.get("const"), value)) {
            return false;
        }
        if (node.get("enum") instanceof List<?> values && !values.stream().anyMatch(v -> JsonValues.equal(v, value))) {
            return false;
        }
        if (value instanceof String text && !checkString(node, text)) {
            return false;
        }
        if (value instanceof Number number && !checkNumber(node, number)) {
            return false;
        }
        if (value instanceof Map<?, ?> object && !checkObject(node, object, depth)) {
            return false;
        }
        if (value instanceof List<?> array && !checkArray(node, array, depth)) {
            return false;
        }
        if (node.get("allOf") instanceof List<?> members) {
            for (Object member : members) {
                if (!check(member, value, depth + 1)) {
                    return false;
                }
            }
        }
        if (node.get("anyOf") instanceof List<?> branches) {
            boolean any = false;
            for (Object branch : branches) {
                if (check(branch, value, depth + 1)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        if (node.get("oneOf") instanceof List<?> branches) {
            int matches = 0;
            for (Object branch : branches) {
                if (check(branch, value, depth + 1)) {
                    matches++;
                }
            }
            if (matches != 1) {
                return false;
            }
        }
        if (node.containsKey("not") && check(node.get("not"), value, depth + 1)) {
            return false;
        }
        if (node.containsKey("if")) {
            Object branch = check(node.get("if"), value, depth + 1) ? node.get("then") : node.get("else");
            if (branch != null && !check(branch, value, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkType(Object type, Object value) {
        if (type == null) {
            return true;
        }
        if (type instanceof String name) {
            return matchesType(name, value);
        }
        if (type instanceof List<?> names) {
            for (Object name : names) {
                if (name instanceof String s && matchesType(s, value)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    static boolean matchesType(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number n && isFinite(n);
            case "integer" -> value instanceof Number n && isInteger(n);
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> false; // TypeBox extensions (bytes/bigint/undefined) never match JSON values.
        };
    }

    private boolean checkString(Map<?, ?> node, String value) {
        int length = value.codePointCount(0, value.length());
        if (node.get("minLength") instanceof Number min && length < min.intValue()) {
            return false;
        }
        if (node.get("maxLength") instanceof Number max && length > max.intValue()) {
            return false;
        }
        if (node.get("pattern") instanceof String pattern && !regex(pattern).matcher(value).find()) {
            return false;
        }
        if (node.get("format") instanceof String format && !SchemaFormats.matches(format, value)) {
            return false;
        }
        return true;
    }

    private boolean checkNumber(Map<?, ?> node, Number number) {
        BigDecimal value = JsonValues.decimal(number);
        if (node.get("minimum") instanceof Number min && value.compareTo(JsonValues.decimal(min)) < 0) {
            return false;
        }
        if (node.get("maximum") instanceof Number max && value.compareTo(JsonValues.decimal(max)) > 0) {
            return false;
        }
        if (node.get("exclusiveMinimum") instanceof Number min && value.compareTo(JsonValues.decimal(min)) <= 0) {
            return false;
        }
        if (node.get("exclusiveMaximum") instanceof Number max && value.compareTo(JsonValues.decimal(max)) >= 0) {
            return false;
        }
        if (node.get("multipleOf") instanceof Number divisor && !isMultiple(value, JsonValues.decimal(divisor))) {
            return false;
        }
        return true;
    }

    private boolean checkObject(Map<?, ?> node, Map<?, ?> value, int depth) {
        Map<?, ?> properties = node.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        if (node.get("required") instanceof List<?> required) {
            for (Object key : required) {
                if (key instanceof String name && !value.containsKey(name)) {
                    return false;
                }
            }
        }
        Map<?, ?> patterns = node.get("patternProperties") instanceof Map<?, ?> p ? p : Map.of();
        for (var entry : value.entrySet()) {
            String key = String.valueOf(entry.getKey());
            boolean matched = properties.containsKey(key);
            if (matched && !check(properties.get(key), entry.getValue(), depth + 1)) {
                return false;
            }
            for (var patternEntry : patterns.entrySet()) {
                String pattern = String.valueOf(patternEntry.getKey());
                if (regex(pattern).matcher(key).find()) {
                    matched = true;
                    if (!check(patternEntry.getValue(), entry.getValue(), depth + 1)) {
                        return false;
                    }
                }
            }
            if (!matched) {
                Object additional = node.get("additionalProperties");
                if (Boolean.FALSE.equals(additional)) {
                    return false;
                }
                if (additional instanceof Map<?, ?> additionalSchema
                        && !check(additionalSchema, entry.getValue(), depth + 1)) {
                    return false;
                }
            }
            if (node.get("propertyNames") != null && !check(node.get("propertyNames"), key, depth + 1)) {
                return false;
            }
        }
        if (node.get("minProperties") instanceof Number min && value.size() < min.intValue()) {
            return false;
        }
        if (node.get("maxProperties") instanceof Number max && value.size() > max.intValue()) {
            return false;
        }
        return checkDependencies(node, value, depth);
    }

    private boolean checkDependencies(Map<?, ?> node, Map<?, ?> value, int depth) {
        Object dependentRequired = node.get("dependentRequired");
        if (dependentRequired instanceof Map<?, ?> dependencies) {
            for (var entry : dependencies.entrySet()) {
                if (!value.containsKey(String.valueOf(entry.getKey()))) {
                    continue;
                }
                if (entry.getValue() instanceof List<?> required) {
                    for (Object key : required) {
                        if (key instanceof String name && !value.containsKey(name)) {
                            return false;
                        }
                    }
                }
            }
        }
        Object dependencies = node.get("dependencies");
        if (dependencies instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!value.containsKey(String.valueOf(entry.getKey()))) {
                    continue;
                }
                Object dependency = entry.getValue();
                if (dependency instanceof List<?> required) {
                    for (Object key : required) {
                        if (key instanceof String name && !value.containsKey(name)) {
                            return false;
                        }
                    }
                } else if (!check(dependency, value, depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean checkArray(Map<?, ?> node, List<?> value, int depth) {
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
            if (schema != null && !check(schema, value.get(i), depth + 1)) {
                return false;
            }
        }
        if (node.get("minItems") instanceof Number min && value.size() < min.intValue()) {
            return false;
        }
        if (node.get("maxItems") instanceof Number max && value.size() > max.intValue()) {
            return false;
        }
        if (Boolean.TRUE.equals(node.get("uniqueItems")) && !isUnique(value)) {
            return false;
        }
        if (node.containsKey("contains")) {
            int min = node.get("minContains") instanceof Number n ? n.intValue() : 1;
            int max = node.get("maxContains") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
            int matches = 0;
            for (Object item : value) {
                if (check(node.get("contains"), item, depth + 1)) {
                    matches++;
                }
            }
            if (matches < min || matches > max) {
                return false;
            }
        }
        return true;
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

    private static boolean isMultiple(BigDecimal value, BigDecimal divisor) {
        if (divisor.signum() == 0) {
            return false;
        }
        return value.remainder(divisor).compareTo(BigDecimal.ZERO) == 0;
    }

    static boolean isFinite(Number number) {
        if (number instanceof Double d) {
            return Double.isFinite(d);
        }
        if (number instanceof Float f) {
            return Float.isFinite(f);
        }
        return true;
    }

    static boolean isInteger(Number number) {
        if (number instanceof Integer || number instanceof Long || number instanceof Short
                || number instanceof Byte || number instanceof java.math.BigInteger) {
            return true;
        }
        if (number instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value == Math.rint(value);
    }

    private static Pattern regex(String source) {
        try {
            return Pattern.compile(source);
        } catch (RuntimeException e) {
            return Pattern.compile("(?!)"); // Uncompilable pattern matches nothing.
        }
    }
}
