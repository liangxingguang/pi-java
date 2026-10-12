package com.pijava.ai.api;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原 docs/66：{@link StrictSampling} —— pi {@code resolveJsonSchemaStrictSampling}
 * （{@code constrained-sampling.ts:208-228}）。
 */
class StrictSamplingTest {

    private static ToolDefinition tool(ConstrainedSampling sampling, Map<String, Object> schema) {
        return new ToolDefinition("t", "d", schema, "t", null, List.of(), "default", sampling);
    }

    private static final Map<String, Object> OBJECT_SCHEMA =
        Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")));

    @Test
    void absentSamplingResolvesToNull() {
        assertThat(StrictSampling.resolveStrict(tool(null, OBJECT_SCHEMA), true)).isNull();
    }

    @Test
    void preferConvertibleResolvesToTrue() {
        var prefer = tool(new JsonSchemaSampling(StrictMode.PREFER), OBJECT_SCHEMA);
        assertThat(StrictSampling.resolveStrict(prefer, true)).isTrue();
    }

    @Test
    void preferUnconvertibleFallsBackToNull() {
        Map<String, Object> badSchema = Map.of("type", "object", "$ref", "#/x");
        var prefer = tool(new JsonSchemaSampling(StrictMode.PREFER), badSchema);

        assertThat(StrictSampling.resolveStrict(prefer, true)).isNull();
    }

    @Test
    void requireUnconvertibleThrowsWithReason() {
        var badSchema = new java.util.LinkedHashMap<String, Object>();
        badSchema.put("type", "object");
        badSchema.put("$ref", "#/x");
        var require = tool(new JsonSchemaSampling(StrictMode.REQUIRE), badSchema);

        assertThatThrownBy(() -> StrictSampling.resolveStrict(require, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Tool \"t\" requires JSON-schema constrained sampling, but "
                + "$ref schemas are unsupported.");
    }

    @Test
    void requireUnsupportedThrows() {
        var require = tool(new JsonSchemaSampling(StrictMode.REQUIRE), OBJECT_SCHEMA);

        assertThatThrownBy(() -> StrictSampling.resolveStrict(require, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Tool \"t\" requires JSON-schema constrained sampling, "
                + "but strict tools are unsupported.");
    }

    @Test
    void preferUnsupportedFallsBackToNull() {
        var prefer = tool(new JsonSchemaSampling(StrictMode.PREFER), OBJECT_SCHEMA);
        assertThat(StrictSampling.resolveStrict(prefer, false)).isNull();
    }

    // ── 批1/#101：provider 专有谓词（Anthropic 关键字集）────────────────

    /** 探测谓词：只拒绝 {@code bad} 键，用于断言三参版的回落/抛出路径。 */
    private static final UnsupportedStrictKeyword REJECT_BAD = (key, value) -> "bad".equals(key);

    @Test
    void preferKeywordRejectedByPredicateFallsBackToNull() {
        var schema = new java.util.LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("bad", 1);
        schema.put("properties", Map.of("x", Map.of("type", "string")));
        var prefer = tool(new JsonSchemaSampling(StrictMode.PREFER), schema);

        assertThat(StrictSampling.resolveStrict(prefer, true, REJECT_BAD)).isNull();
    }

    @Test
    void requireKeywordRejectedByPredicateThrowsWithPiWording() {
        var schema = new java.util.LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("bad", 1);
        schema.put("properties", Map.of());
        var require = tool(new JsonSchemaSampling(StrictMode.REQUIRE), schema);

        assertThatThrownBy(() -> StrictSampling.resolveStrict(require, true, REJECT_BAD))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Tool \"t\" requires JSON-schema constrained sampling, but "
                + "bad: 1 is unsupported.");
    }

    @Test
    void predicateReachesNestedProperties() {
        var schema = new java.util.LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", Map.of("x", Map.of("bad", true, "type", "string")));
        var prefer = tool(new JsonSchemaSampling(StrictMode.PREFER), schema);

        assertThat(StrictSampling.resolveStrict(prefer, true, REJECT_BAD)).isNull();
    }

    @Test
    void nullPredicateBehavesLikeTwoArgForm() {
        assertThat(StrictSampling.resolveStrict(
            tool(new JsonSchemaSampling(StrictMode.PREFER), OBJECT_SCHEMA), true, null)).isTrue();
    }
}
