package com.pijava.evals.judge;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.pijava.evals.harness.HarnessRun;

/**
 * 批 32（docs/44 §4）：结构化输出判分器 —— 把 {@link HarnessRun#output()} 与用例期望
 * 做严格深比较，对位 pi {@code StructuredOutputJudge({expected, match:"strict", allowExtras:false})}。
 *
 * <p>期望是 {@code String} 时按文本逐字比较（模型回复通常不是合法 JSON，不尝试解析）；
 * 期望是结构化值时把 {@code output()} 当 JSON 树解析（解析失败按原始文本比较）。
 * 对象逐键、数组逐元素、数字按容差比较；{@code allowExtras=false} 时多余键即失败。
 * 分数只有 1/0，理由给出首个差异路径。</p>
 *
 * <p>多判分器共用一份 {@code expected} 时，若它是一个含 {@code expectedOutput} 键的
 * 对象，本判分器只比较该键的值（与 {@link ToolCallJudge} 读取同一对象的
 * {@code expectedTools} 键相对）。</p>
 */
public final class StructuredOutputJudge implements Judge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double DEFAULT_TOLERANCE = 1e-9;
    private static final int SNIPPET_LIMIT = 200;

    private final boolean allowExtras;
    private final double numericTolerance;

    /** Strict comparison, no extra fields, exact numbers. */
    public StructuredOutputJudge() {
        this(false, DEFAULT_TOLERANCE);
    }

    /**
     * @param allowExtras whether keys absent from {@code expected} are tolerated
     */
    public StructuredOutputJudge(boolean allowExtras) {
        this(allowExtras, DEFAULT_TOLERANCE);
    }

    /**
     * @param allowExtras      whether keys absent from {@code expected} are tolerated
     * @param numericTolerance absolute tolerance for numeric comparison
     */
    public StructuredOutputJudge(boolean allowExtras, double numericTolerance) {
        this.allowExtras = allowExtras;
        this.numericTolerance = numericTolerance;
    }

    @Override
    public JudgeResult evaluate(HarnessRun run, Object expected) {
        var actual = run == null ? null : run.output();
        if (expected instanceof Map<?, ?> map && map.containsKey("expectedOutput")) {
            expected = map.get("expectedOutput");
        }
        if (expected instanceof String expectedText) {
            return evaluateText(actual, expectedText);
        }
        var expectedNode = MAPPER.valueToTree(expected);
        var actualNode = parse(actual);
        var diff = compareNodes("$", expectedNode, actualNode, allowExtras, numericTolerance);
        if (diff == null) {
            return new JudgeResult(1.0, "structured output matches", metadata());
        }
        return new JudgeResult(0.0, diff.describe(), metadata());
    }

    private Map<String, Object> metadata() {
        return Map.of("match", "strict", "allowExtras", allowExtras);
    }

    private static JudgeResult evaluateText(Object actual, String expected) {
        var actualText = actual == null ? null : actual.toString();
        if (Objects.equals(expected, actualText)) {
            return new JudgeResult(1.0, "text output matches", Map.of("match", "text"));
        }
        return new JudgeResult(0.0,
            "text mismatch: expected \"" + expected + "\", actual \"" + actualText + "\"",
            Map.of("match", "text"));
    }

    private static JsonNode parse(Object actual) {
        if (actual instanceof JsonNode node) {
            return node;
        }
        if (actual instanceof String text) {
            try {
                return MAPPER.readTree(text);
            } catch (JsonProcessingException ignored) {
                return MAPPER.getNodeFactory().textNode(text);
            }
        }
        return MAPPER.valueToTree(actual);
    }

    /** Convert a plain Java value to a JSON tree (package-private for {@link ToolCallJudge}). */
    static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * Deep-compare two JSON trees.
     *
     * @param path             JSON path of the current node, for the rationale
     * @param expected         expected tree (nullable)
     * @param actual           observed tree (nullable)
     * @param allowExtras      whether extra object keys are tolerated
     * @param numericTolerance absolute tolerance for numbers
     * @return first difference, or {@code null} when equal
     */
    static Diff compareNodes(String path, JsonNode expected, JsonNode actual,
                             boolean allowExtras, double numericTolerance) {
        if (expected == null || actual == null) {
            return Objects.equals(expected, actual) ? null : new Diff(path, expected, actual);
        }
        if (expected.isObject() && actual.isObject()) {
            return compareObjects(path, expected, actual, allowExtras, numericTolerance);
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) {
                return new Diff(path + ".length",
                    IntNode.valueOf(expected.size()), IntNode.valueOf(actual.size()));
            }
            for (int index = 0; index < expected.size(); index++) {
                var diff = compareNodes(path + "[" + index + "]", expected.get(index), actual.get(index),
                    allowExtras, numericTolerance);
                if (diff != null) {
                    return diff;
                }
            }
            return null;
        }
        if (expected.isNumber() && actual.isNumber()) {
            if (Math.abs(expected.asDouble() - actual.asDouble()) > numericTolerance) {
                return new Diff(path, expected, actual);
            }
            return null;
        }
        return expected.equals(actual) ? null : new Diff(path, expected, actual);
    }

    private static Diff compareObjects(String path, JsonNode expected, JsonNode actual,
                                       boolean allowExtras, double numericTolerance) {
        var fields = expected.fieldNames();
        while (fields.hasNext()) {
            var field = fields.next();
            var actualChild = actual.get(field);
            if (actualChild == null) {
                return new Diff(path + "." + field, expected.get(field), null);
            }
            var diff = compareNodes(path + "." + field, expected.get(field), actualChild,
                allowExtras, numericTolerance);
            if (diff != null) {
                return diff;
            }
        }
        if (!allowExtras) {
            var actualFields = actual.fieldNames();
            while (actualFields.hasNext()) {
                var field = actualFields.next();
                if (!expected.has(field)) {
                    return new Diff(path + "." + field, null, actual.get(field));
                }
            }
        }
        return null;
    }

    private static String snippet(JsonNode node) {
        if (node == null) {
            return "(missing)";
        }
        if (node.isNull()) {
            return "null";
        }
        var text = node.isTextual() ? node.asText() : node.toString();
        return text.length() > SNIPPET_LIMIT ? text.substring(0, SNIPPET_LIMIT) + "..." : text;
    }

    /** First difference found by {@link #compareNodes}. */
    record Diff(String path, JsonNode expected, JsonNode actual) {

        String describe() {
            return "mismatch at " + path + ": expected " + snippet(expected) + ", actual " + snippet(actual);
        }
    }
}
