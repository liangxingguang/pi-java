package com.pijava.codemode;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.codemode.api.CodeModeDeclarations;
import com.pijava.codemode.api.CodeModeSchemaTypes.DeclarationOptions;
import com.pijava.codemode.api.CodeModeSchemaTypes.RenderOptions;
import com.pijava.codemode.api.CodeModeSchemaTypes.ToolSchema;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link CodeModeDeclarations}. */
class CodeModeDeclarationsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rendersPrimitivesLiteralsAndUnions() throws IOException {
        assertThat(CodeModeDeclarations.schemaToType(json("{\"type\":\"string\"}"))).isEqualTo("string");
        assertThat(CodeModeDeclarations.schemaToType(json("{\"type\":\"integer\"}"))).isEqualTo("number");
        assertThat(CodeModeDeclarations.schemaToType(json("{\"type\":[\"string\",\"null\"]}")))
                .isEqualTo("string | null");
        assertThat(CodeModeDeclarations.schemaToType(json("{\"const\":\"a\"}"))).isEqualTo("\"a\"");
        assertThat(CodeModeDeclarations.schemaToType(json("{\"enum\":[\"a\",1,null]}"))).isEqualTo("\"a\" | 1 | null");
        assertThat(CodeModeDeclarations.schemaToType(json("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}")))
                .isEqualTo("string | number");
        assertThat(CodeModeDeclarations.schemaToType(json("{\"anyOf\":[{\"type\":\"string\"},{}]}")))
                .isEqualTo("unknown");
        assertThat(CodeModeDeclarations.schemaToType(json("""
                {"allOf":[{"anyOf":[{"type":"string"},{"type":"number"}]},{"const":1}]}""")))
                .isEqualTo("(string | number) & 1");
        assertThat(CodeModeDeclarations.schemaToType(json("{\"$ref\":\"#/defs/x\"}"))).isEqualTo("unknown");
        assertThat(CodeModeDeclarations.schemaToType(json("true"))).isEqualTo("unknown");
        assertThat(CodeModeDeclarations.schemaToType(json("false"))).isEqualTo("never");
    }

    @Test
    void rendersObjectsOnOneLineWithSortedProperties() throws IOException {
        String sorted = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},"
                + "\"max-lines\":{\"type\":\"number\"}},\"required\":[\"city\"],\"additionalProperties\":false}";
        assertThat(CodeModeDeclarations.schemaToType(json(sorted)))
                .isEqualTo("{ city: string; \"max-lines\"?: number; }");
        assertThat(CodeModeDeclarations.schemaToType(json("""
                {"type":"object","additionalProperties":{"type":"number"}}""")))
                .isEqualTo("{ [key: string]: number; }");
        assertThat(CodeModeDeclarations.schemaToType(json("{\"type\":\"object\"}")))
                .isEqualTo("{ [key: string]: unknown; }");
        assertThat(CodeModeDeclarations.schemaToType(json("""
                {"type":"object","properties":{},"additionalProperties":false}""")))
                .isEqualTo("{}");
    }

    @Test
    void putsPropertyDescriptionsOnCommentLines() throws IOException {
        String weather = "{\"type\":\"object\",\"properties\":{\"weather\":{\"type\":\"array\","
                + "\"description\":\"look up weather for a given list of locations\",\"items\":{\"type\":\"object\","
                + "\"properties\":{\"location\":{\"type\":\"string\"}},\"required\":[\"location\"]}}},"
                + "\"required\":[\"weather\"]}";
        assertThat(CodeModeDeclarations.schemaToType(json(weather)))
                .isEqualTo("{\n  // look up weather for a given list of locations\n"
                        + "  weather: Array<{ location: string; }>;\n}");
        String outer = "{\"type\":\"object\",\"properties\":{\"outer\":{\"type\":\"object\","
                + "\"description\":\"Outer\",\"properties\":{\"inner\":{\"type\":\"string\","
                + "\"description\":\"Inner\"}}}}}";
        assertThat(CodeModeDeclarations.schemaToType(json(outer)))
                .isEqualTo("{\n  // Outer\n  outer?: {\n    // Inner\n    inner?: string;\n  };\n}");
    }

    @Test
    void putsConstraintsOnPropertyCommentLines() throws IOException {
        String expected = String.join("\n",
                "{",
                "  // @maxLength 8 @pattern ^[a-z]+$",
                "  id: string;",
                "  name?: string;",
                "  // @maxItems 5 @uniqueItems @deprecated",
                "  tags?: Array<string>;",
                "  // Wait in milliseconds.",
                "  // @integer @minimum 0 @maximum 30000 @default 0",
                "  timeoutMs?: number;",
                "}");
        String schema = "{\"type\":\"object\",\"properties\":{\"timeoutMs\":{\"type\":\"integer\","
                + "\"minimum\":0,\"maximum\":30000,\"default\":0,\"description\":\"Wait in milliseconds.\"},"
                + "\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z]+$\",\"maxLength\":8},"
                + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":5,"
                + "\"uniqueItems\":true,\"deprecated\":true},\"name\":{\"type\":\"string\"}},\"required\":[\"id\"]}";
        assertThat(CodeModeDeclarations.schemaToType(json(schema))).isEqualTo(expected);
    }

    @Test
    void readsConstraintsOfOptionalFieldsFromNonNullBranch() throws IOException {
        String limit = "{\"type\":\"object\",\"properties\":{\"limit\":{\"anyOf\":[{\"type\":\"integer\","
                + "\"minimum\":1,\"maximum\":100},{\"type\":\"null\"}],\"default\":null,\"title\":\"Limit\"}}}";
        assertThat(CodeModeDeclarations.schemaToType(json(limit)))
                .isEqualTo("{\n  // @integer @minimum 1 @maximum 100\n  limit?: number | null;\n}");
        String n = "{\"type\":\"object\",\"properties\":{\"n\":{\"anyOf\":[{\"type\":\"integer\","
                + "\"minimum\":10,\"maximum\":50,\"format\":\"int32\"},{\"type\":\"null\"}],"
                + "\"minimum\":0,\"maximum\":20,\"format\":\"port\"}}}";
        assertThat(CodeModeDeclarations.schemaToType(json(n)))
                .isEqualTo("{\n  // @integer @minimum 10 @maximum 20 @format port @format int32\n"
                        + "  n?: number | null;\n}");
    }

    @Test
    void readsDraft04BooleanExclusiveBounds() throws IOException {
        String expected = String.join("\n",
                "{",
                "  // @maximum 8 @exclusiveMaximum 5",
                "  m?: number | null;",
                "  // @integer @exclusiveMinimum 0 @maximum 10",
                "  n?: number;",
                "}");
        String schema = "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\","
                + "\"minimum\":0,\"exclusiveMinimum\":true,\"maximum\":10,\"exclusiveMaximum\":false},"
                + "\"m\":{\"anyOf\":[{\"type\":\"number\",\"maximum\":5,\"exclusiveMaximum\":true},"
                + "{\"type\":\"null\"}],\"maximum\":8}}}";
        assertThat(CodeModeDeclarations.schemaToType(json(schema))).isEqualTo(expected);
    }

    @Test
    void leavesOutLongTagsAndEscapesLineTerminators() throws IOException {
        assertThat(CodeModeDeclarations.schemaToType(json("{\"type\":\"object\",\"properties\":{\"config\":"
                + "{\"type\":\"object\",\"default\":{\"text\":\"" + "x".repeat(100) + "\"}}}}")))
                .isEqualTo("{ config?: { [key: string]: unknown; }; }");
        String expected = String.join("\n",
                "{",
                "  // a",
                "  // b",
                "  // @pattern ^[\\u000d\\u000a\\u2028\\u2029]$ @default \"\\u2028\"",
                "  sep?: string;",
                "}");
        String sep = "{\"type\":\"object\",\"properties\":{\"sep\":{\"type\":\"string\","
                + "\"pattern\":\"^[\\r\\n\\u2028\\u2029]$\",\"default\":\"\\u2028\","
                + "\"description\":\"a\\u2028b\"}}}";
        assertThat(CodeModeDeclarations.schemaToType(json(sep))).isEqualTo(expected);
    }

    @Test
    void dropsTagsBeforeRenderingOverBudgetTypeAsUnknown() throws IOException {
        JsonNode schema = json("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1}}}");
        assertThat(CodeModeDeclarations.schemaToType(schema))
                .isEqualTo("{\n  // @integer @minimum 1\n  n?: number;\n}");
        assertThat(CodeModeDeclarations.schemaToType(schema, new DeclarationOptions(20)))
                .isEqualTo("{ n?: number; }");
        assertThat(CodeModeDeclarations.schemaToType(schema, new DeclarationOptions(10)))
                .isEqualTo("unknown");
    }

    @Test
    void resolvesLocalReferencesAndStopsAtRecursiveOnes() throws IOException {
        JsonNode schema = json("""
                {"type":"object","properties":{"item":{"$ref":"#/$defs/Item"},
                "legacy":{"$ref":"#/definitions/Legacy"},"remote":{"$ref":"https://example.com/schema.json"}},
                "required":["item"],"$defs":{"Item":{"type":"object",
                "properties":{"id":{"type":"string"},"parent":{"$ref":"#/$defs/Item"}},"required":["id"]}},
                "definitions":{"Legacy":{"enum":["a","b"]}}}""");
        assertThat(CodeModeDeclarations.schemaToType(schema))
                .isEqualTo("{ item: { id: string; parent?: unknown; }; legacy?: \"a\" | \"b\"; remote?: unknown; }");
    }

    @Test
    void rendersArraysAndTuples() throws IOException {
        assertThat(CodeModeDeclarations.schemaToType(json("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}")))
                .isEqualTo("Array<string>");
        assertThat(CodeModeDeclarations.schemaToType(json("""
                {"type":"array","prefixItems":[{"type":"string"},{"type":"number"}]}""")))
                .isEqualTo("[string, number]");
        assertThat(CodeModeDeclarations.schemaToType(json("{\"type\":\"array\"}"))).isEqualTo("unknown[]");
    }

    @Test
    void rendersTypesOverBudgetAsUnknown() throws IOException {
        StringBuilder properties = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            if (i > 0) {
                properties.append(',');
            }
            properties.append("\"field").append(i).append("\":{\"type\":\"string\"}");
        }
        JsonNode schema = json("{\"type\":\"object\",\"properties\":{" + properties + "}}");
        assertThat(CodeModeDeclarations.schemaToType(schema, new DeclarationOptions(100))).isEqualTo("unknown");
        assertThat(CodeModeDeclarations.schemaToType(schema)).contains("field49?: string;");
    }

    @Test
    void resolvesLocalReferences() throws IOException {
        JsonNode item = json("{\"type\":\"string\"}");
        JsonNode root = json("{\"$defs\":{\"item\":{\"type\":\"string\"},\"a/b\":{\"type\":\"string\"},"
                + "\"c~d\":{\"type\":\"string\"},\"e f\":{\"type\":\"string\"}}}");
        assertThat(CodeModeDeclarations.resolveSchemaRef("#", root)).isEqualTo(root);
        assertThat(CodeModeDeclarations.resolveSchemaRef("#/$defs/item", root)).isEqualTo(item);
        assertThat(CodeModeDeclarations.resolveSchemaRef("#/$defs/a~1b", root)).isEqualTo(item);
        assertThat(CodeModeDeclarations.resolveSchemaRef("#/$defs/c~0d", root)).isEqualTo(item);
        assertThat(CodeModeDeclarations.resolveSchemaRef("#/$defs/e%20f", root)).isEqualTo(item);
        assertThat(CodeModeDeclarations.resolveSchemaRef("other.json#/$defs/item", root)).isNull();
        assertThat(CodeModeDeclarations.resolveSchemaRef("#/$defs/missing", root)).isNull();
        assertThat(CodeModeDeclarations.resolveSchemaRef("#/$defs/%E0%A4%A", root)).isNull();
    }

    @Test
    void rendersSignaturesWithNormalizedIdentifiers() throws IOException {
        ToolSchema input = new ToolSchema("hidden-dynamic-tool", null,
                json("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"],"
                        + "\"additionalProperties\":false}"),
                json("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"]}"));
        assertThat(CodeModeDeclarations.renderToolSignature(input))
                .isEqualTo("hidden_dynamic_tool(args: { city: string; }): Promise<{ ok: boolean; }>;");
        assertThat(CodeModeDeclarations.renderToolSignature(new ToolSchema("free", null, null, null)))
                .isEqualTo("free(args: unknown): Promise<unknown>;");
    }

    @Test
    void rendersMcpCallToolResultShapes() throws IOException {
        JsonNode output = json("{\"type\":\"object\",\"properties\":{\"content\":"
                + "{\"type\":\"array\",\"items\":{\"type\":\"object\"}},"
                + "\"isError\":{\"type\":\"boolean\"},\"_meta\":{\"type\":\"object\"}},"
                + "\"required\":[\"content\"]}");
        ToolSchema tool = new ToolSchema("plain", null, json("{\"type\":\"object\",\"properties\":{}}"), output);
        assertThat(CodeModeDeclarations.renderToolSignature(tool))
                .isEqualTo("plain(args: { [key: string]: unknown; }): "
                        + "Promise<{ _meta?: { [key: string]: unknown; }; content: Array<{ [key: string]: unknown; }>; "
                        + "isError?: boolean; }>;");
    }

    @Test
    void rendersPerToolSample() throws IOException {
        ToolSchema tool = new ToolSchema("foo", "bar", json("{\"type\":\"string\"}"), null);
        assertThat(CodeModeDeclarations.renderToolSample(tool)).isEqualTo("bar\n\n"
                + "codemode tool declaration:\n```ts\ndeclare const tools: { "
                + "foo(args: string): Promise<unknown>; };\n```");
    }

    @Test
    void rendersToolsAndGlobals() throws IOException {
        RenderOptions options = new RenderOptions(
                List.of(
                        new ToolSchema("read", "Read a file.\nSecond line.",
                                json("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},"
                                        + "\"required\":[\"path\"]}"),
                                json("{\"type\":\"string\"}")),
                        new ToolSchema("remote-api", null, null, null)),
                List.of(new ToolSchema("attach", "Attach it.", json("{\"type\":\"string\"}"), null, null)));
        String expected = String.join("\n",
                "declare const tools: {",
                "  /**",
                "   * Read a file.",
                "   * Second line.",
                "   */",
                "  read(args: { path: string; }): Promise<string>;",
                "  remote_api(args: unknown): Promise<unknown>;",
                "};",
                "",
                "/** Attach it. */",
                "declare function attach(args: string): Promise<unknown>;");
        assertThat(CodeModeDeclarations.renderDeclarations(options)).isEqualTo(expected);
    }

    @Test
    void rendersNamespacedGlobalsAndExplicitSignatures() {
        RenderOptions options = new RenderOptions(List.of(), List.of(
                new ToolSchema("models.list", "List models.", null, null, "(type: string): Promise<string[]>"),
                new ToolSchema("models.get", null, jsonUnchecked("{\"type\":\"string\"}"), null, null),
                new ToolSchema("plain", null, null, null, "(): void")));
        String expected = String.join("\n",
                "declare function plain(): void;",
                "",
                "declare const models: {",
                "  /** List models. */",
                "  list(type: string): Promise<string[]>;",
                "  get(args: string): Promise<unknown>;",
                "};");
        assertThat(CodeModeDeclarations.renderDeclarations(options)).isEqualTo(expected);
    }

    @Test
    void escapesCommentTerminatorsInDescriptions() {
        RenderOptions options = new RenderOptions(
                List.of(new ToolSchema("x", "a */ b", null, null)), List.of());
        assertThat(CodeModeDeclarations.renderDeclarations(options)).contains("/** a *\\/ b */");
    }

    private static JsonNode json(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    private static JsonNode jsonUnchecked(String text) {
        try {
            return json(text);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
