package com.pijava.ai.api;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/66：{@link ConstrainedSampling} 的 JSON 线格与 pi 逐字
 * （{@code types.ts:590-594}）。
 */
class ConstrainedSamplingJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void preferSerializesToPiShape() throws Exception {
        ConstrainedSampling sampling = new JsonSchemaSampling(StrictMode.PREFER);

        assertThat(MAPPER.writeValueAsString(sampling))
            .isEqualTo("{\"type\":\"json_schema\",\"strict\":\"prefer\"}");
    }

    @Test
    void requireSerializesToPiLiteral() throws Exception {
        assertThat(MAPPER.writeValueAsString(new JsonSchemaSampling(StrictMode.REQUIRE)))
            .isEqualTo("{\"type\":\"json_schema\",\"strict\":\"require\"}");
    }

    @Test
    void readsPiShapeBack() throws Exception {
        var parsed = MAPPER.readValue(
            "{\"type\":\"json_schema\",\"strict\":\"require\"}", ConstrainedSampling.class);

        assertThat(parsed).isInstanceOf(JsonSchemaSampling.class);
        assertThat(((JsonSchemaSampling) parsed).strict()).isEqualTo(StrictMode.REQUIRE);
    }

    @Test
    void toolDefinitionAndDeclarationCarrySampling() {
        var sampling = new JsonSchemaSampling(StrictMode.PREFER);
        var definition = new ToolDefinition("read", "d", java.util.Map.of("type", "object"),
            "read", null, java.util.List.of(), "default", sampling);
        var declaration = new ToolDeclaration("read", "d",
            java.util.Map.of("type", "object"), sampling);

        assertThat(definition.constrainedSampling()).isSameAs(sampling);
        assertThat(declaration.constrainedSampling()).isSameAs(sampling);
    }
}
