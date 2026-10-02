package com.pijava.ai.api;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/66：{@link StrictJsonSchema} —— pi {@code makeStrictJsonSchema}
 * （{@code constrained-sampling.ts:117-127}）逐条移植。
 */
class StrictJsonSchemaTest {

    @Test
    void objectSchemaGetsAllRequiredAndAdditionalPropertiesFalse() {
        var input = Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string"),
                "timeout", Map.of("type", "integer")),
            "required", List.of("command"));

        var strict = StrictJsonSchema.convert(input);

        assertThat(strict.get("additionalProperties")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var requiredNames = (List<String>) strict.get("required");
        assertThat(requiredNames).containsExactlyInAnyOrder("command", "timeout");
        // 非 required 属性被包成 null anyOf；原 required 属性不动。
        assertThat(strict.get("properties"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
            .extracting("command", "timeout")
            .containsExactly(Map.of("type", "string"),
                Map.of("anyOf", List.of(Map.of("type", "integer"), Map.of("type", "null"))));
    }

    @Test
    void nestedPropertiesAreRecursivelyStrictified() {
        Map<String, Object> input = Map.of(
            "type", "object",
            "properties", Map.of("items", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of("path", Map.of("type", "string"))))));

        var strict = StrictJsonSchema.convert(input);

        // 非 required 的 array 属性先被包成 null anyOf，首变体才是原数组 schema。
        var wrapped = (Map<?, ?>) ((Map<?, ?>) strict.get("properties")).get("items");
        var items = (Map<?, ?>) ((List<?>) wrapped.get("anyOf")).get(0);
        var inner = (Map<?, ?>) items.get("items");
        assertThat(inner.get("additionalProperties")).isEqualTo(false);
        assertThat(inner.get("required")).isEqualTo(List.of("path"));
    }

    @Test
    void unsupportedKeysThrow() {
        for (var key : List.of("$ref", "$defs", "definitions", "allOf", "oneOf",
                "patternProperties", "dependentSchemas", "dependencies",
                "unevaluatedProperties", "propertyNames", "contains", "prefixItems",
                "not", "if", "then", "else")) {
            var input = Map.of("type", "object", key, Map.of());
            assertThatThrownBy(() -> StrictJsonSchema.convert(input))
                .isInstanceOf(UnsupportedStrictJsonSchemaException.class)
                .hasMessageContaining(key);
        }
    }

    @Test
    void objectAnyOfUnionsAndTuplesThrow() {
        Map<String, Object> objectUnion = Map.of("anyOf", List.of(
            Map.of("type", "object", "properties", Map.of()), Map.of("type", "string")));
        assertThatThrownBy(() -> StrictJsonSchema.convert(objectUnion))
            .isInstanceOf(UnsupportedStrictJsonSchemaException.class)
            .hasMessageContaining("unions");

        Map<String, Object> tuple = Map.of("type", "object",
            "properties", Map.of("x", Map.of("items", List.of(Map.of("type", "string")))));
        assertThatThrownBy(() -> StrictJsonSchema.convert(tuple))
            .isInstanceOf(UnsupportedStrictJsonSchemaException.class)
            .hasMessageContaining("tuple");
    }

    @Test
    void propertiesRequireObjectType() {
        Map<String, Object> input = Map.of("properties", Map.of("x", Map.of("type", "string")));
        assertThatThrownBy(() -> StrictJsonSchema.convert(input))
            .isInstanceOf(UnsupportedStrictJsonSchemaException.class)
            .hasMessageContaining("type object");
    }

    @Test
    void schemaValuedAdditionalPropertiesThrows() {
        var input = Map.of("type", "object",
            "properties", Map.of("x", Map.of("type", "string")),
            "additionalProperties", Map.of("type", "string"));
        assertThatThrownBy(() -> StrictJsonSchema.convert(input))
            .isInstanceOf(UnsupportedStrictJsonSchemaException.class)
            .hasMessageContaining("additionalProperties");
    }

    @Test
    void requiredUnknownPropertyThrows() {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("type", "object");
        input.put("properties", Map.of("a", Map.of("type", "string")));
        input.put("required", List.of("b"));
        assertThatThrownBy(() -> StrictJsonSchema.convert(input))
            .isInstanceOf(UnsupportedStrictJsonSchemaException.class)
            .hasMessageContaining("unknown property");
    }

    @Test
    void anyOfNullPropertyIsNotRewrapped() {
        var input = Map.of(
            "type", "object",
            "properties", Map.of("x", Map.of("anyOf", List.of(
                Map.of("type", "string"), Map.of("type", "null")))));

        var strict = StrictJsonSchema.convert(input);

        var x = (Map<?, ?>) ((Map<?, ?>) strict.get("properties")).get("x");
        assertThat(x.get("anyOf")).asList().hasSize(2);
    }

    @Test
    void rootMustBeObject() {
        assertThatThrownBy(() -> StrictJsonSchema.convert(Map.of("type", "array")))
            .isInstanceOf(UnsupportedStrictJsonSchemaException.class)
            .hasMessageContaining("object");
    }

    @Test
    void doesNotMutateTheInput() {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("type", "object");
        input.put("properties", Map.of("x", Map.of("type", "string")));

        StrictJsonSchema.convert(input);

        assertThat(input).doesNotContainKey("additionalProperties");
        assertThat(input.get("required")).isNull();
    }
}
