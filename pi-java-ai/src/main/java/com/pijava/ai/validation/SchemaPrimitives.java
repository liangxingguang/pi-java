package com.pijava.ai.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/** TypeBox's and Ajv's primitive conversions used by the coercion passes. */
final class SchemaPrimitives {

    static final Object NOT_CONVERTED = new Object();
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9007199254740991L);
    private static final Pattern BIGINT_TEXT = Pattern.compile("^-?(0|[1-9]\\d*)n$");
    private static final Pattern BIGINT_DECIMAL = Pattern.compile("^-?(0|[1-9]\\d*)\\.\\d+$");
    private static final Pattern BIGINT_INTEGER = Pattern.compile("^-?(0|[1-9]\\d*)$");

    private SchemaPrimitives() {}

    /** A literal (`const`, an enum value) taken from a value of another type that converts to exactly it. */
    static Object convertLiteral(Object literal, Object value) {
        if (literal == value || JsonValues.equal(literal, value)) {
            return value;
        }
        Object result = NOT_CONVERTED;
        if (literal instanceof String) {
            result = tryString(value);
        } else if (literal instanceof Number) {
            result = tryNumber(value);
        } else if (literal instanceof Boolean) {
            result = tryBoolean(value);
        } else if (literal instanceof BigInteger) {
            result = tryBigInteger(value);
        }
        return result != NOT_CONVERTED && JsonValues.equal(result, literal) ? result : value;
    }

    /** TypeBox's TryString. */
    static Object tryString(Object value) {
        if (value instanceof BigInteger || value instanceof Boolean || isFiniteNumber(value)) {
            return String.valueOf(value instanceof Number n && n.doubleValue() == n.longValue()
                    ? n.longValue() : value);
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return value;
        }
        return NOT_CONVERTED;
    }

    /** TypeBox's TryNumber. */
    static Object tryNumber(Object value) {
        if (value instanceof BigInteger big) {
            return big.abs().compareTo(MAX_SAFE_INTEGER) <= 0 ? big.doubleValue() : NOT_CONVERTED;
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (isFiniteNumber(value)) {
            return value;
        }
        if (value == null) {
            return 0;
        }
        if (!(value instanceof String text)) {
            return NOT_CONVERTED;
        }
        if (text.trim().isEmpty()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(text.trim());
            return Double.isFinite(parsed) ? parsed : NOT_CONVERTED;
        } catch (NumberFormatException e) {
            String lower = text.toLowerCase();
            if (lower.equals("false")) {
                return 0;
            }
            if (lower.equals("true")) {
                return 1;
            }
            BigInteger big = bigIntOfString(text);
            return big != null && big.abs().compareTo(MAX_SAFE_INTEGER) <= 0
                    ? big.doubleValue() : NOT_CONVERTED;
        }
    }

    /** TypeBox's TryBigInt. */
    static Object tryBigInteger(Object value) {
        if (value instanceof BigInteger big) {
            return big;
        }
        if (value instanceof Boolean flag) {
            return flag ? BigInteger.ONE : BigInteger.ZERO;
        }
        if (value instanceof Number number) {
            return BigInteger.valueOf((long) Math.rint(number.doubleValue()));
        }
        if (value == null) {
            return BigInteger.ZERO;
        }
        if (!(value instanceof String text)) {
            return NOT_CONVERTED;
        }
        BigInteger big = bigIntOfString(text);
        if (big != null) {
            return big;
        }
        String lower = text.toLowerCase();
        if (lower.equals("false")) {
            return BigInteger.ZERO;
        }
        return lower.equals("true") ? BigInteger.ONE : NOT_CONVERTED;
    }

    private static BigInteger bigIntOfString(String text) {
        if (BIGINT_TEXT.matcher(text).matches()) {
            return new BigInteger(text.substring(0, text.length() - 1));
        }
        if (BIGINT_DECIMAL.matcher(text).matches()) {
            return new BigInteger(text.split("\\.")[0]);
        }
        if (BIGINT_INTEGER.matcher(text).matches()) {
            return new BigInteger(text);
        }
        return null;
    }

    /** TypeBox's TryBoolean. */
    static Object tryBoolean(Object value) {
        if (value instanceof BigInteger big) {
            return big.equals(BigInteger.ZERO) ? false : big.equals(BigInteger.ONE) ? true : NOT_CONVERTED;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (isFiniteNumber(value)) {
            double d = ((Number) value).doubleValue();
            return d == 0 ? false : d == 1 ? true : NOT_CONVERTED;
        }
        if (value == null) {
            return false;
        }
        if (!(value instanceof String text)) {
            return NOT_CONVERTED;
        }
        String lower = text.toLowerCase();
        if (lower.equals("false") || text.equals("0")) {
            return false;
        }
        if (lower.equals("true") || text.equals("1")) {
            return true;
        }
        return NOT_CONVERTED;
    }

    /** TypeBox's TryNull; {@code null} is a successful conversion here. */
    static Object tryNull(Object value) {
        if (value instanceof BigInteger big) {
            return big.equals(BigInteger.ZERO) ? null : NOT_CONVERTED;
        }
        if (value instanceof Boolean flag) {
            return flag ? NOT_CONVERTED : null;
        }
        if (isFiniteNumber(value)) {
            return ((Number) value).doubleValue() == 0 ? null : NOT_CONVERTED;
        }
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            return NOT_CONVERTED;
        }
        String lower = text.toLowerCase();
        return lower.equals("undefined") || lower.equals("null") || text.isEmpty() || text.equals("0")
                ? null : NOT_CONVERTED;
    }

    static Object orValue(Object result, Object value) {
        return result == NOT_CONVERTED ? value : result;
    }

    /** Ajv-style primitive coercion by type name (pass 3). */
    static Object coercePrimitive(Object value, String type) {
        return switch (type) {
            case "number", "integer" -> {
                if (value == null) {
                    yield 0;
                }
                if (value instanceof String text && !text.trim().isEmpty()) {
                    try {
                        double parsed = Double.parseDouble(text.trim());
                        if (type.equals("number") ? Double.isFinite(parsed)
                                : parsed == Math.rint(parsed)) {
                            yield parsed;
                        }
                    } catch (NumberFormatException ignored) {
                        // Leave the value unchanged.
                    }
                }
                if (value instanceof Boolean flag) {
                    yield flag ? 1 : 0;
                }
                yield value;
            }
            case "boolean" -> {
                if (value == null) {
                    yield false;
                }
                if ("true".equals(value) || isNumber(value, 1)) {
                    yield true;
                }
                if ("false".equals(value) || isNumber(value, 0)) {
                    yield false;
                }
                yield value;
            }
            case "string" -> {
                if (value == null) {
                    yield "";
                }
                if (value instanceof Number || value instanceof Boolean) {
                    yield String.valueOf(value instanceof Number n && n.doubleValue() == n.longValue()
                            ? n.longValue() : value);
                }
                yield value;
            }
            case "null" -> "".equals(value) || isNumber(value, 0) || Boolean.FALSE.equals(value)
                    ? null : value;
            default -> value;
        };
    }

    /** JavaScript's `Math.trunc` for the values JSON can carry. */
    static Object truncate(Object number) {
        if (number instanceof Double d) {
            if (d == Math.rint(d)) {
                return Math.abs(d) < 9.22e18 ? d.longValue() : d;
            }
            return d.longValue(); // Math.trunc yields an integer-valued JS number.
        }
        if (number instanceof Float f) {
            return f.longValue();
        }
        if (number instanceof BigDecimal decimal) {
            return decimal.toBigInteger();
        }
        return number;
    }

    static boolean isFiniteNumber(Object value) {
        return value instanceof Number number && SchemaCheck.isFinite(number);
    }

    private static boolean isNumber(Object value, double expected) {
        return value instanceof Number n && n.doubleValue() == expected;
    }
}
