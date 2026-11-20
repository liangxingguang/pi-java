package com.pijava.ai.validation;

import java.util.List;
import java.util.Map;

/**
 * Minimal {@code JSON.stringify(value, null, 2)} renderer.
 *
 * <p>Tool validation errors are model-visible; pi embeds the received arguments with JavaScript's
 * two-space pretty printer. Jackson's default printer writes {@code "key" : value}, so the
 * validation error uses this renderer instead.
 */
final class JsJson {

    private JsJson() {}

    /** Render {@code value} the way {@code JSON.stringify(value, null, 2)} does. */
    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    /** Render {@code value} the way {@code JSON.stringify(value)} does. */
    static String compact(Object value) {
        StringBuilder out = new StringBuilder();
        writeCompact(value, out);
        return out.toString();
    }

    private static void writeCompact(Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                writeCompact(entry.getValue(), out);
            }
            out.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeCompact(list.get(i), out);
            }
            out.append(']');
            return;
        }
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String text) {
            writeString(text, out);
            return;
        }
        if (value instanceof Boolean flag) {
            out.append(flag);
            return;
        }
        if (value instanceof Number number) {
            writeNumber(number, out);
            return;
        }
        writeString(String.valueOf(value), out);
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String text) {
            writeString(text, out);
            return;
        }
        if (value instanceof Boolean flag) {
            out.append(flag);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeObject(map, out, depth);
            return;
        }
        if (value instanceof List<?> list) {
            writeArray(list, out, depth);
            return;
        }
        if (value instanceof Character c) {
            writeString(String.valueOf(c), out);
            return;
        }
        if (value instanceof Number number) {
            writeNumber(number, out);
            return;
        }
        // Unknown host object: fall back to its string form, wrapped as a JSON string.
        writeString(String.valueOf(value), out);
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, depth + 1);
            writeString(String.valueOf(entry.getKey()), out);
            out.append(": ");
            write(entry.getValue(), out, depth + 1);
        }
        newline(out, depth);
        out.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder out, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            newline(out, depth + 1);
            write(list.get(i), out, depth + 1);
        }
        newline(out, depth);
        out.append(']');
    }

    private static void newline(StringBuilder out, int depth) {
        out.append('\n');
        out.append("  ".repeat(depth));
    }

    private static void writeNumber(Number number, StringBuilder out) {
        if (number instanceof Integer || number instanceof Long || number instanceof Short
                || number instanceof Byte) {
            out.append(number.longValue());
            return;
        }
        if (number instanceof java.math.BigInteger big) {
            out.append(big);
            return;
        }
        if (number instanceof java.math.BigDecimal decimal) {
            out.append(decimal.stripTrailingZeros().toPlainString());
            return;
        }
        double value = number.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            out.append("null"); // JSON.stringify turns non-finite numbers into null.
            return;
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e21) {
            out.append((long) value);
            return;
        }
        out.append(Double.toString(value).replace("E", "e"));
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
