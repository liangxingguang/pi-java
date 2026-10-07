package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.protocol.McpTool;
import com.pijava.mcp.protocol.ProgressNotification;
import com.pijava.mcp.protocol.ToolAnnotations;
import com.pijava.mcp.protocol.content.CallToolResult;
import com.pijava.mcp.protocol.content.McpContentBlock;
import com.pijava.mcp.runtime.McpToolCaller;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code createMcpToolDefinition}（{@code tools.ts:260-299}）与 schema 助手。
 */
class McpToolDefinitionTest {

    private static McpTool tool(String name, String title, String description,
                               Map<String, Object> inputSchema, Map<String, Object> outputSchema,
                               ToolAnnotations annotations) {
        return new McpTool(name, title, description, inputSchema, outputSchema, annotations, null, null);
    }


    private static McpToolDefinition definitions(McpTool tool, McpToolCaller caller) {
        return McpToolDefinition.create(new McpToolDefinition.Options(
                "files", tool, "mcp__files__" + tool.name(), McpExposure.CODEMODE,
                "mcp__files", 30_000L, () -> caller, null));
    }

    // ------------------------------------------------------------------ creation

    @Test
    void mapsTheDefinitionsFields() {
        var definition = definitions(tool("echo", "Echo", "  says it back  ",
                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))),
                Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "number"))),
                new ToolAnnotations(null, true, false, null, true)), (name, args, options) -> null);

        assertThat(definition.name()).isEqualTo("mcp__files__echo");
        assertThat(definition.label()).isEqualTo("files/echo");
        assertThat(definition.description()).isEqualTo("says it back");
        assertThat(definition.parameters()).isEqualTo(
                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))));
        // codemode is exposed as deferred to the tool layer.
        assertThat(definition.exposure()).isEqualTo(McpToolExposure.DEFERRED);
        assertThat(definition.namespace()).isEqualTo("mcp__files");
        assertThat(definition.annotations()).isEqualTo(
                new McpToolDefinition.Annotations(true, false, null, true));
        assertThat(definition.outputSchema())
                .containsEntry("required", List.of("content"))
                .extracting(schema -> ((Map<?, ?>) schema).get("properties"))
                .asString().contains("structuredContent");
    }

    @Test
    void fallsBackToTheAnnotationTitleThenToAStandardSentence() {
        assertThat(definitions(tool("echo", null, null, Map.of(), null,
                new ToolAnnotations("Echo tool", null, null, null, null)), (n, a, o) -> null)
                .description()).isEqualTo("Echo tool");
        assertThat(definitions(tool("echo", null, "   ", Map.of(), null, null), (n, a, o) -> null)
                .description()).isEqualTo("MCP tool echo from server files");
    }

    @Test
    void completesAnInputSchemaThatSomeProvidersWouldReject() {
        var definition = definitions(tool("echo", null, "d", Map.of(), null, null), (n, a, o) -> null);
        assertThat(definition.parameters()).containsEntry("type", "object")
                .containsEntry("properties", Map.of());
    }

    @Test
    void annotationsAreNullWhenTheToolHasNone() {
        assertThat(definitions(tool("echo", null, "d", Map.of(), null, null), (n, a, o) -> null)
                .annotations()).isNull();
        assertThat(definitions(tool("echo", null, "d", Map.of(), null,
                new ToolAnnotations(null, null, null, null, null)), (n, a, o) -> null)
                .annotations()).isNull();
    }

    @Test
    void theOutputSchemaOmitsStructuredContentWithoutOne() {
        var definition = definitions(tool("echo", null, "d", Map.of(), null, null), (n, a, o) -> null);
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) definition.outputSchema().get("properties");
        assertThat(properties).containsOnlyKeys("content", "isError", "_meta");
    }

    // ----------------------------------------------------------------- execution

    @Test
    void runsInParallelLikePiToolsWithoutAnExecutionMode() {
        var definition = definitions(tool("echo", null, "d", Map.of(), null, null), (n, a, o) -> null);
        assertThat(definition.agent().executionMode()).isInstanceOf(ExecutionMode.Parallel.class);
    }

    @Test
    void aCallReadsTheCallerOnEveryInvocation() throws Exception {
        var seen = new ArrayList<String>();
        var callers = List.of(
                caller("first", seen), caller("second", seen));
        var index = new int[] {0};
        var definition = McpToolDefinition.create(new McpToolDefinition.Options(
                "files", tool("echo", null, "d", Map.of(), null, null), "mcp__files__echo",
                McpExposure.DIRECT, "mcp__files", 30_000L,
                () -> callers.get(index[0]++), null));

        definition.agent().execute("id", Map.of(), null, null, null);
        definition.agent().execute("id", Map.of(), null, null, null);

        assertThat(seen).containsExactly("first", "second");
    }

    @Test
    void progressBecomesAnUpdateWithPiTextAndDetails() throws Exception {
        var updates = new ArrayList<ToolResult<McpToolDetails>>();
        var definition = definitions(tool("echo", null, "d", Map.of(), null, null),
                (name, args, options) -> {
                    var progress = options.onProgress();
                    progress.accept(new ProgressNotification("t", 3, 10.0, null));
                    progress.accept(new ProgressNotification("t", 4, null, "halfway"));
                    return CompletableFuture.completedFuture(content("ok"));
                });

        definition.agent().execute("id", Map.of(), null, updates::add, null);

        assertThat(updates).hasSize(2);
        // A JS number prints without a trailing `.0`.
        assertThat(((ContentBlock.TextContent) updates.get(0).content().get(0)).text())
                .isEqualTo("Progress 3/10");
        assertThat(((ContentBlock.TextContent) updates.get(1).content().get(0)).text())
                .isEqualTo("halfway");
        assertThat(updates.get(0).details()).isEqualTo(McpToolDetails.of("files", "echo"));
    }

    private static McpToolCaller caller(String marker, List<String> seen) {
        return (name, args, options) -> {
            seen.add(marker);
            return CompletableFuture.completedFuture(content("ok"));
        };
    }

    private static CallToolResult content(String text) {
        return new CallToolResult(List.of(new McpContentBlock.Text(text, null, null)), null, null, null);
    }

    @Test
    void anEmptyParameterMapIsSentAsSuch() throws Exception {
        var seen = new ArrayList<Map<String, Object>>();
        var definition = definitions(tool("echo", null, "d", Map.of(), null, null),
                (name, args, options) -> {
                    seen.add(args);
                    return CompletableFuture.completedFuture(content("ok"));
                });

        definition.agent().execute("id", null, null, null, null);

        assertThat(seen).containsExactly(Map.of());
    }

    /** The adapter must not treat a missing request options object as absent. */
    @Test
    void theRequestCarriesTheToolsTimeout() throws Exception {
        var seen = new ArrayList<McpRequestOptions>();
        var definition = definitions(tool("echo", null, "d", Map.of(), null, null),
                (name, args, options) -> {
                    seen.add(options);
                    return CompletableFuture.completedFuture(content("ok"));
                });

        definition.agent().execute("id", Map.of(), null, null, null);

        assertThat(seen.get(0).timeoutMs()).isEqualTo(30_000L);
        assertThat(seen.get(0).onProgress()).isNull();
    }
}
