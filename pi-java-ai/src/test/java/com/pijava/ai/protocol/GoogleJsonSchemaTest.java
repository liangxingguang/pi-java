package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #111（docs/17 §7）：Gemini JSON-Schema 的 {@code const} → 单值 {@code enum} 改写，
 * 逐字对位 pi {@code google-shared.ts:390-430}。
 */
class GoogleJsonSchemaTest {

    private static Map<String, Object> map(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void topLevelStringConstBecomesASingleValueEnum() {
        var rewritten = GoogleJsonSchema.rewriteConstAsEnum(
            map("type", "string", "const", "x"));

        assertThat(rewritten).isEqualTo(map("type", "string", "enum", List.of("x")));
    }

    @Test
    void numberConstBecomesASingleValueEnum() {
        var rewritten = GoogleJsonSchema.rewriteConstAsEnum(
            map("type", "number", "const", 3));

        assertThat(rewritten).isEqualTo(map("type", "number", "enum", List.of(3)));
    }

    @Test
    void nonScalarConstIsLeftAlone() {
        for (Object value : List.of(true, Map.of("a", 1), List.of(1))) {
            var schema = map("type", "object", "const", value);
            assertThat(GoogleJsonSchema.rewriteConstAsEnum(schema)).isEqualTo(schema);
        }
        // null 也不是 string/number。
        var withNull = map("type", "string", "const", null);
        assertThat(GoogleJsonSchema.rewriteConstAsEnum(withNull)).isEqualTo(withNull);
    }

    @Test
    void existingEnumWinsAndConstStays() {
        var schema = map("type", "string", "const", "x", "enum", List.of("x", "y"));

        assertThat(GoogleJsonSchema.rewriteConstAsEnum(schema)).isEqualTo(schema);
    }

    @Test
    void nestedPropertiesAndArrayItemsAreRewritten() {
        var schema = map(
            "type", "object",
            "properties", map(
                "flag", map("type", "string", "const", "on"),
                "list", map("type", "array",
                    "items", map("type", "number", "const", 7))));

        var rewritten = (Map<?, ?>) GoogleJsonSchema.rewriteConstAsEnum(schema);

        var properties = (Map<?, ?>) rewritten.get("properties");
        assertThat((Map<?, ?>) properties.get("flag"))
            .isEqualTo(map("type", "string", "enum", List.of("on")));
        var list = (Map<?, ?>) properties.get("list");
        assertThat((Map<?, ?>) list.get("items"))
            .isEqualTo(map("type", "number", "enum", List.of(7)));
    }

    @Test
    void propertyNamesAreNotSchemas() {
        // properties 下的键名 "const" 是属性名，不是 schema 关键字 ⇒ 不得改写。
        var schema = map(
            "type", "object",
            "properties", map("const", map("type", "string")));

        assertThat(GoogleJsonSchema.rewriteConstAsEnum(schema)).isEqualTo(schema);
    }

    @Test
    void combinatorsAreRewrittenRecursively() {
        var schema = map(
            "oneOf", List.of(map("type", "string", "const", "a"),
                map("type", "string", "const", "b")),
            "anyOf", List.of(map("type", "number", "const", 1)),
            "allOf", List.of(map("type", "string", "const", "c")));

        var rewritten = (Map<?, ?>) GoogleJsonSchema.rewriteConstAsEnum(schema);

        var oneOf = new ArrayList<Object>((List<?>) rewritten.get("oneOf"));
        assertThat(oneOf).containsExactly(
            map("type", "string", "enum", List.of("a")),
            map("type", "string", "enum", List.of("b")));
        var anyOf = new ArrayList<Object>((List<?>) rewritten.get("anyOf"));
        assertThat(anyOf).containsExactly(map("type", "number", "enum", List.of(1)));
        var allOf = new ArrayList<Object>((List<?>) rewritten.get("allOf"));
        assertThat(allOf).containsExactly(map("type", "string", "enum", List.of("c")));
    }
}
