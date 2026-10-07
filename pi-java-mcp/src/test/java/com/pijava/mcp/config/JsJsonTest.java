package com.pijava.mcp.config;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.McpJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JsJson} 与 {@code JSON.stringify(value, null, indent)} 的逐字等价
 * （{@code config.ts:241}）。
 */
class JsJsonTest {

    private static String stringify(String json, String indent) throws Exception {
        return JsJson.stringify(McpJson.mapper().readTree(json), indent);
    }

    @Test
    void writesNestedContainersLikeJsonStringify() throws Exception {
        assertThat(stringify("{\"a\":1,\"b\":[1,2],\"c\":{},\"d\":[],\"e\":\"x\"}", "  "))
                .isEqualTo("""
                        {
                          "a": 1,
                          "b": [
                            1,
                            2
                          ],
                          "c": {},
                          "d": [],
                          "e": "x"
                        }""");
    }

    @Test
    void writesScalarsWithJsonEscaping() throws Exception {
        var node = McpJson.mapper().createObjectNode().put("s", "a\"b\\c\nd\te" + (char) 1);
        assertThat(JsJson.stringify(node, "  "))
                .isEqualTo("{\n  \"s\": \"a\\\"b\\\\c\\nd\\te" + "\\" + "u0001\"\n}");
    }

    @Test
    void keepsIntegersWithoutADecimalPoint() throws Exception {
        var node = McpJson.mapper().createObjectNode().put("timeout", 60).put("ratio", 1.5);
        assertThat(JsJson.stringify(node, "  ")).isEqualTo("{\n  \"timeout\": 60,\n  \"ratio\": 1.5\n}");
    }

    @Test
    void detectsTheIndentOfTheFirstIndentedLine() {
        assertThat(JsJson.detectIndent("{\n    \"a\": 1\n}")).isEqualTo("    ");
        assertThat(JsJson.detectIndent("{\n\t\"a\": 1\n}")).isEqualTo("\t");
        assertThat(JsJson.detectIndent("{\n \"a\": 1\n}")).isEqualTo(" ");
        assertThat(JsJson.detectIndent("{\"a\": 1}")).isEqualTo("  ");
        assertThat(JsJson.detectIndent(null)).isEqualTo("  ");
    }
}
