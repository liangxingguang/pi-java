package com.pijava.examples;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 批 28（docs/40 T8，F2 取证）：{@code JSON.stringify} 语义的序列化臂——
 * Jackson DefaultPrettyPrinter 的空容器 {@code { }}/{@code [ ]}、键序（无整型键重排）、
 * 数字形（{@code 1.0}）均与 JS 有字节差 ⇒ 手写。
 *
 * <p>覆盖面：null／boolean／number／string／array／object。数字遵循 JS 规则
 * （整型无小数点；|v|∈[1e-6, 1e21) 走十进制，否则指数形 {@code 1e+21}/{@code 1e-7}）。
 * 对象键序：整型下标键（{@code "0".."2^32-2"} 无前导零）升序在前，其余插入序
 * （ECMA-262 [[OwnPropertyKeys]]）。字符串转义：{@code \" \\ \b \t \n \f \r}＋
 * 其余控制符 {@code } 小写；非 ASCII 原样；{@code /} 不转义。</p>
 */
final class JsJson {

    private JsJson() {}

    /** {@code JSON.stringify(value)}（紧凑）。 */
    static String stringify(JsonNode node) {
        var out = new StringBuilder();
        write(node, out, -1, 0);
        return out.toString();
    }

    /** {@code JSON.stringify(value, null, indent)}。 */
    static String stringify(JsonNode node, int indent) {
        var out = new StringBuilder();
        write(node, out, indent, 0);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out, int indent, int depth) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNumber()) {
            out.append(number(node));
        } else if (node.isTextual()) {
            string(node.textValue(), out);
        } else if (node.isArray()) {
            array(node, out, indent, depth);
        } else if (node.isObject()) {
            object(node, out, indent, depth);
        } else {
            // binary/pojo 等（JSON.stringify 对 undefined/函数产 undefined——对象成员被丢、
            // 数组位产 null；车道载荷不会出现，按 null 直写并注记）
            out.append("null");
        }
    }

    private static void array(JsonNode node, StringBuilder out, int indent, int depth) {
        if (node.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        for (int i = 0; i < node.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            newlineIndent(out, indent, depth + 1);
            write(node.get(i), out, indent, depth + 1);
        }
        newlineIndent(out, indent, depth);
        out.append(']');
    }

    private static void object(JsonNode node, StringBuilder out, int indent, int depth) {
        if (node.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        var first = true;
        for (var key : orderedKeys(node)) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newlineIndent(out, indent, depth + 1);
            string(key, out);
            out.append(':');
            if (indent >= 0) {
                out.append(' ');
            }
            write(node.get(key), out, indent, depth + 1);
        }
        newlineIndent(out, indent, depth);
        out.append('}');
    }

    /** ECMA-262 键序：整型下标键升序在前，其余插入序。 */
    private static java.util.List<String> orderedKeys(JsonNode node) {
        var intKeys = new ArrayList<Map.Entry<Long, String>>();
        var rest = new ArrayList<String>();
        node.fieldNames().forEachRemaining(key -> {
            var index = arrayIndex(key);
            if (index != null) {
                intKeys.add(Map.entry(index, key));
            } else {
                rest.add(key);
            }
        });
        intKeys.sort(Comparator.comparingLong(Map.Entry::getKey));
        var out = new ArrayList<String>(intKeys.size() + rest.size());
        intKeys.forEach(e -> out.add(e.getValue()));
        out.addAll(rest);
        return out;
    }

    /** JS canonical array index：十进制无前导零，0 ≤ n < 2^32-1；否则 null。 */
    private static Long arrayIndex(String key) {
        if (key.isEmpty() || key.length() > 10 || (key.length() > 1 && key.charAt(0) == '0')) {
            return null;
        }
        long value = 0;
        for (var i = 0; i < key.length(); i++) {
            var ch = key.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
            value = value * 10 + (ch - '0');
        }
        return value < 4294967295L ? value : null;
    }

    private static void newlineIndent(StringBuilder out, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        out.append('\n');
        out.append(" ".repeat(indent * depth));
    }

    /** JS Number#toString 对位（byte 级）：整型无小数点；[1e-6,1e21) 十进制；否则指数。 */
    private static String number(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue().toString();
        }
        var value = node.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null"; // JSON.stringify(NaN/Infinity) ⇒ null
        }
        var abs = Math.abs(value);
        if (abs >= 1e-6 && abs < 1e21) {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
        if (value == 0) {
            return "0";
        }
        // 指数形：Double.toString "1.0E21" ⇒ "1e+21"；尾 ".0" 剥掉
        var raw = Double.toString(value);
        var ePos = raw.indexOf('E');
        var mantissa = raw.substring(0, ePos);
        if (mantissa.endsWith(".0")) {
            mantissa = mantissa.substring(0, mantissa.length() - 2);
        }
        var exponent = raw.substring(ePos + 1);
        if (!exponent.startsWith("-")) {
            exponent = "+" + exponent;
        }
        return mantissa + "e" + exponent;
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (var i = 0; i < value.length(); i++) {
            var ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }
}
