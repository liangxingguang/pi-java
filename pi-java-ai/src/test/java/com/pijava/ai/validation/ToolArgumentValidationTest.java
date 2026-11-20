package com.pijava.ai.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** pi-schema's model-visible coercion and error contract for tool arguments. */
class ToolArgumentValidationTest {

    private static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void coercesNumbersToStringsForStringProperties() {
        var schema = objectSchema(Map.of("text", Map.of("type", "string")), "text");
        Object parsed = ToolArgumentValidation.parse("echo", schema, args("text", 123));
        assertThat(((Map<?, ?>) parsed).get("text")).isEqualTo("123");
    }

    @Test
    void coercesNumericStringsToIntegersAndTruncatesFractions() {
        var schema = objectSchema(Map.of("limit", Map.of("type", "integer")), "limit");
        Object parsed = ToolArgumentValidation.parse("find", schema, args("limit", "3.5"));
        assertThat(((Map<?, ?>) parsed).get("limit")).isEqualTo(3L);
    }

    @Test
    void wrapsASingleValueIntoAnArray() {
        var schema = objectSchema(Map.of("paths", Map.of("type", "array", "items", Map.of("type", "string"))),
                "paths");
        Object parsed = ToolArgumentValidation.parse("read", schema, args("paths", "src/a.txt"));
        assertThat(((Map<?, ?>) parsed).get("paths")).isEqualTo(List.of("src/a.txt"));
    }

    @Test
    void convertsNullToTheStringNullForARequiredString() {
        var schema = objectSchema(Map.of("text", Map.of("type", "string")), "text");
        Object parsed = ToolArgumentValidation.parse("echo", schema, args("text", null));
        assertThat(((Map<?, ?>) parsed).get("text")).isEqualTo("null");
    }

    @Test
    void dropsAnOptionalNullWhoseSchemaRejectsNull() {
        var schema = Map.<String, Object>of(
                "type", "object",
                "properties", Map.of("text", Map.of("type", "string")));
        Object parsed = ToolArgumentValidation.parse("echo", schema, args("text", null));
        assertThat(parsed).isEqualTo(Map.of());
    }

    @Test
    void reportsMissingRequiredAtTheField() {
        var schema = objectSchema(Map.of("command", Map.of("type", "string")), "command");
        assertThatThrownBy(() -> ToolArgumentValidation.parse("bash", schema, args()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Validation failed for tool \"bash\":\n  - command: missing");
    }

    @Test
    void reportsTypeMismatchWithPiText() {
        var schema = objectSchema(Map.of("text", Map.of("type", "string")), "text");
        assertThatThrownBy(() -> ToolArgumentValidation.parse("echo", schema, args("text", Map.of("x", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("- text: expected string, got object")
                .hasMessageContaining("Received arguments:");
    }

    @Test
    void reportsUnknownFieldForClosedObjects() {
        var schema = objectSchema(Map.of("text", Map.of("type", "string")), "text");
        var closed = new LinkedHashMap<>(schema);
        closed.put("additionalProperties", false);
        assertThatThrownBy(() -> ToolArgumentValidation.parse("echo", closed, args("extra", true)))
                .hasMessageContaining("- extra: unknown field");
    }

    @Test
    void reportsEnumAndMinimumMessages() {
        var schema = objectSchema(Map.of(
                "mode", Map.of("type", "string", "enum", List.of("a", "b")),
                "limit", Map.of("type", "integer", "minimum", 1)), "mode");
        assertThatThrownBy(() -> ToolArgumentValidation.parse("x", schema, args("mode", "c", "limit", 0)))
                .hasMessageContaining("- mode: expected one of [\"a\",\"b\"]")
                .hasMessageContaining("- limit: expected >= 1");
    }

    @Test
    void formatsNestedArrayPathsAsDottedPaths() {
        var schema = objectSchema(Map.of(
                "edits", Map.of("type", "array", "items", Map.of(
                        "type", "object",
                        "properties", Map.of("oldText", Map.of("type", "string")),
                        "required", List.of("oldText")))), "edits");
        var edits = List.of(Map.of());
        assertThatThrownBy(() -> ToolArgumentValidation.parse("edit", schema, args("edits", edits)))
                .hasMessageContaining("- edits.0.oldText: missing")
                .hasMessageNotContaining("/edits/0/oldText");
    }

    @Test
    void acceptsToolArgumentsThroughALocalRef() {
        var schema = Map.<String, Object>of(
                "type", "object",
                "properties", Map.of("item", Map.of("$ref", "#/$defs/item")),
                "required", List.of("item"),
                "$defs", Map.of("item", Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", List.of("name"))));
        Object parsed = ToolArgumentValidation.parse("x", schema, args("item", args("name", "a")));
        assertThat(parsed).isEqualTo(Map.of("item", Map.of("name", "a")));
    }

    @Test
    void rejectsADanglingRefOnlyAtItsPlace() {
        var schema = Map.<String, Object>of(
                "type", "object",
                "properties", Map.of("t", Map.of("$ref", "#/$defs/Missing")),
                "$defs", Map.of());
        assertThatThrownBy(() -> ToolArgumentValidation.parse("x", schema, args("t", 1)))
                .hasMessageContaining("- t: unresolved reference #/$defs/Missing");
    }

    @Test
    void keepsTheTerminalStreamErrorBeforeSchemaRules() {
        var schema = objectSchema(Map.of("command", Map.of("type", "string")), "command");
        assertThatThrownBy(() -> ToolArgumentValidation.parse("bash", schema, args(), "bad-json"))
                .hasMessage("Invalid arguments for tool \"bash\": bad-json");
    }

    @Test
    void printsReceivedArgumentsLikeJavaScript() {
        var schema = objectSchema(Map.of("text", Map.of("type", "string")), "text");
        assertThatThrownBy(() -> ToolArgumentValidation.parse("echo", schema, args("text", Map.of("x", 1))))
                .hasMessageEndingWith("""
                        Received arguments:
                        {
                          "text": {
                            "x": 1
                          }
                        }""");
    }
}
