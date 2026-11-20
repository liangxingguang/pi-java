package com.pijava.ai.validation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** JSON value helpers shared by the check, coercion and error walker. */
final class JsonValues {

    private JsonValues() {}

    /** Deep equality with JavaScript number semantics ({@code 1 === 1.0}). */
    static boolean equal(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Number a && right instanceof Number b) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (var entry : a.entrySet()) {
                Object key = entry.getKey();
                if (!b.containsKey(key) || !equal(entry.getValue(), b.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!equal(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    static BigDecimal decimal(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof java.math.BigInteger integer) {
            return new BigDecimal(integer);
        }
        return new BigDecimal(number.toString());
    }

    static boolean isPlainObject(Object value) {
        return value instanceof Map<?, ?>;
    }

    /** Deep copy of JSON-shaped values, so a tool cannot mutate the transcript's arguments. */
    static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new java.util.LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            var copy = new java.util.ArrayList<Object>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    static java.util.Map<?, ?> mapAt(java.util.Map<?, ?> node, String key) {
        return node.get(key) instanceof java.util.Map<?, ?> map ? map : java.util.Map.of();
    }

    static java.util.List<?> listAt(java.util.Map<?, ?> node, String key) {
        return node.get(key) instanceof java.util.List<?> list ? list : java.util.List.of();
    }

    static java.util.Map<String, Object> copy(java.util.Map<?, ?> source) {
        var copy = new java.util.LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    static java.util.regex.Pattern regex(String source) {
        try {
            return java.util.regex.Pattern.compile(source);
        } catch (RuntimeException e) {
            return java.util.regex.Pattern.compile("(?!)");
        }
    }

    static java.util.List<?> tuplePrefix(java.util.Map<?, ?> node) {
        if (node.get("items") instanceof java.util.List<?> tuple) {
            return tuple;
        }
        return node.get("prefixItems") instanceof java.util.List<?> prefix ? prefix : null;
    }

    static Object itemSchemaAt(java.util.Map<?, ?> node, int index) {
        if (node.get("items") instanceof java.util.List<?> tuple) {
            return index < tuple.size() ? tuple.get(index) : null;
        }
        if (node.get("prefixItems") instanceof java.util.List<?> prefix && index < prefix.size()) {
            return prefix.get(index);
        }
        return node.get("items");
    }

    /** The name pi's error messages use for a JSON value. */
    static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Number number) {
            if (!SchemaCheck.isFinite(number)) {
                return String.valueOf(number);
            }
            return SchemaCheck.isInteger(number) ? "integer" : "number";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        return "object";
    }
}
