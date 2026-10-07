package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.protocol.ListResourceTemplatesResult;
import com.pijava.mcp.protocol.ListResourcesResult;
import com.pijava.mcp.protocol.ReadResourceResult;
import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;
import com.pijava.mcp.protocol.content.ResourceContents;
import com.pijava.mcp.runtime.McpResourceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三个资源工具（{@code resources.ts:194-342}）。
 */
class McpResourceToolsTest {

    /** A scripted server; {@code failure} makes every list call fail. */
    private static final class FakeServer implements McpResourceServer {

        private final String name;
        private List<Resource> resources = List.of();
        private List<ResourceTemplate> templates = List.of();
        private @Nullable String nextCursor;
        private @Nullable RuntimeException failure;
        private List<ResourceContents> contents = List.of();

        private FakeServer(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long timeoutMs() {
            return 1_000L;
        }

        @Override
        public CompletableFuture<ListResourcesResult> resourcesPage(@Nullable String cursor,
                                                                    McpRequestOptions options) {
            return failure != null ? CompletableFuture.failedFuture(failure)
                    : CompletableFuture.completedFuture(
                            new ListResourcesResult(resources, nextCursor, null));
        }

        @Override
        public CompletableFuture<ListResourceTemplatesResult> resourceTemplatesPage(
                @Nullable String cursor, McpRequestOptions options) {
            return failure != null ? CompletableFuture.failedFuture(failure)
                    : CompletableFuture.completedFuture(
                            new ListResourceTemplatesResult(templates, nextCursor, null));
        }

        @Override
        public CompletableFuture<List<Resource>> allResources(McpRequestOptions options) {
            return failure != null ? CompletableFuture.failedFuture(failure)
                    : CompletableFuture.completedFuture(resources);
        }

        @Override
        public CompletableFuture<List<ResourceTemplate>> allResourceTemplates(
                McpRequestOptions options) {
            return failure != null ? CompletableFuture.failedFuture(failure)
                    : CompletableFuture.completedFuture(templates);
        }

        @Override
        public CompletableFuture<ReadResourceResult> readResource(String uri,
                                                                  McpRequestOptions options) {
            return CompletableFuture.completedFuture(new ReadResourceResult(contents, null));
        }
    }

    private static Resource resource(String uri, String name) {
        return new Resource(uri, name, null, null, null, null, null, Map.of("trace", "x"));
    }

    private static ResourceTemplate template(String uriTemplate, String name) {
        return new ResourceTemplate(uriTemplate, name, null, null, null, null, null);
    }

    private static List<McpToolDefinition> tools(McpResourceServer... servers) {
        return McpResourceTools.create(new McpResourceTools.Options(
                McpExposure.DIRECT, () -> List.of(servers)));
    }

    private static Map<String, Object> call(McpToolDefinition definition,
                                            @Nullable Map<String, Object> params) throws Exception {
        var result = definition.agent().execute("id", params, null, null, null);
        @SuppressWarnings("unchecked")
        var structured = (Map<String, Object>) result.structuredContent();
        return structured;
    }

    /** 结果里每个内容块是独立的，模型看到的是它们的文本拼接。 */
    private static String shown(ToolResult<McpToolDetails> result) {
        return McpResultContent.textOf(result.content());
    }

    // ------------------------------------------------------------------- listing

    @Test
    void listsOnePageOfOneServer() throws Exception {
        var files = new FakeServer("files");
        files.resources = List.of(resource("file:///a.txt", "a"));
        files.nextCursor = "next";
        var list = tools(files).get(0);

        var payload = call(list, Map.of("server", "files"));

        assertThat(payload).containsEntry("server", "files").containsEntry("nextCursor", "next");
        @SuppressWarnings("unchecked")
        var resources = (List<Map<String, Object>>) payload.get("resources");
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0)).containsEntry("server", "files")
                .containsEntry("uri", "file:///a.txt");
    }

    @Test
    void aListingWithoutCursorOmitsTheKey() throws Exception {
        var files = new FakeServer("files");
        files.resources = List.of(resource("file:///a.txt", "a"));
        var payload = call(tools(files).get(0), Map.of("server", "files"));
        assertThat(payload).doesNotContainKey("nextCursor");
    }

    @Test
    void aCursorWithoutAServerIsRejected() {
        assertThatThrownBy(() -> call(tools(new FakeServer("files")).get(0), Map.of("cursor", "next")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cursor can only be used when a server is specified");
    }

    @Test
    void everyServerIsListedInNameOrder() throws Exception {
        var zebra = new FakeServer("zebra");
        zebra.resources = List.of(resource("file:///z.txt", "z"));
        var alpha = new FakeServer("alpha");
        alpha.resources = List.of(resource("file:///a.txt", "a"));

        var payload = call(tools(zebra, alpha).get(0), Map.of());

        assertThat(payload).doesNotContainKey("server");
        @SuppressWarnings("unchecked")
        var resources = (List<Map<String, Object>>) payload.get("resources");
        assertThat(resources).extracting(entry -> entry.get("server"))
                .containsExactly("alpha", "zebra");
    }

    @Test
    void oneFailingServerBecomesAnErrorEntryAndTheRestStillList() throws Exception {
        var broken = new FakeServer("broken");
        broken.failure = new IllegalStateException("connection refused");
        var files = new FakeServer("files");
        files.resources = List.of(resource("file:///a.txt", "a"));

        var payload = call(tools(broken, files).get(0), Map.of());

        assertThat(payload).containsKey("errors");
        @SuppressWarnings("unchecked")
        var errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("server", "broken")
                .containsEntry("error", "connection refused");
        @SuppressWarnings("unchecked")
        var resources = (List<Map<String, Object>>) payload.get("resources");
        assertThat(resources).hasSize(1);
    }

    @Test
    void aListingWithoutErrorsOmitsTheKey() throws Exception {
        var payload = call(tools(new FakeServer("files")).get(0), Map.of());
        assertThat(payload).doesNotContainKey("errors");
    }

    @Test
    void listedItemsDropMetaAndIconsAndPutTheServerFirst() throws Exception {
        var files = new FakeServer("files");
        files.resources = List.of(new Resource("file:///a.txt", "a", "T", "D", "text/plain",
                12.0, null, Map.of("trace", "x")));
        var payload = call(tools(files).get(0), Map.of("server", "files"));

        @SuppressWarnings("unchecked")
        var listed = ((List<Map<String, Object>>) payload.get("resources")).get(0);
        assertThat(listed).doesNotContainKey("_meta").doesNotContainKey("icons");
        assertThat(new ArrayList<>(listed.keySet()).get(0)).isEqualTo("server");
        assertThat(listed).containsEntry("name", "a").containsEntry("mimeType", "text/plain");
    }

    @Test
    void mcpAppResourcesAreLeftOut() throws Exception {
        var files = new FakeServer("files");
        files.resources = List.of(resource("ui://widget", "widget"), resource("file:///a.txt", "a"));
        var payload = call(tools(files).get(0), Map.of("server", "files"));

        @SuppressWarnings("unchecked")
        var resources = (List<Map<String, Object>>) payload.get("resources");
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0)).containsEntry("uri", "file:///a.txt");
    }

    @Test
    void anUnknownServerSaysWhichOnesHaveResources() {
        var files = new FakeServer("files");
        assertThatThrownBy(() -> call(tools(files).get(0), Map.of("server", "nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP server \"nope\" has no resources. Servers with resources: files");
    }

    @Test
    void withNoServersAtAllTheMessageHasNoList() {
        assertThatThrownBy(() -> call(tools().get(0), Map.of("server", "nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP server \"nope\" has no resources");
    }

    @Test
    void templatesAreListedUnderTheirOwnKey() throws Exception {
        var files = new FakeServer("files");
        files.templates = List.of(template("file:///{id}", "t"));
        var payload = call(tools(files).get(1), Map.of("server", "files"));

        assertThat(payload).containsKey("resourceTemplates").doesNotContainKey("resources");
    }

    // -------------------------------------------------------------------- reading

    @Test
    void readingOneResourceShowsItsText() throws Exception {
        var files = new FakeServer("files");
        files.contents = List.of(new ResourceContents.Text("file:///a.txt", "text/plain", "hello", null));
        var read = tools(files).get(2);

        var result = read.agent().execute("id", Map.of("server", "files", "uri", "file:///a.txt"),
                null, null, null);

        assertThat(shown(result)).isEqualTo("hello");
        assertThat(result.details()).isEqualTo(McpToolDetails.of("files", "read_mcp_resource"));
        @SuppressWarnings("unchecked")
        var structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured).containsEntry("server", "files")
                .containsEntry("uri", "file:///a.txt");
    }

    @Test
    void severalContentsAreEachLabeledWithTheirUri() throws Exception {
        var files = new FakeServer("files");
        files.contents = List.of(
                new ResourceContents.Text("file:///a.txt", null, "one", null),
                new ResourceContents.Text("file:///b.txt", null, "two", null));
        var read = tools(files).get(2);

        var result = read.agent().execute("id", Map.of("server", "files", "uri", "file:///"),
                null, null, null);

        assertThat(shown(result)).isEqualTo("file:///a.txt:\none\nfile:///b.txt:\ntwo");
    }

    @Test
    void oneContentHasNoUriLabel() throws Exception {
        var files = new FakeServer("files");
        files.contents = List.of(new ResourceContents.Text("file:///a.txt", null, "one", null));
        var read = tools(files).get(2);

        var result = read.agent().execute("id", Map.of("server", "files", "uri", "file:///a.txt"),
                null, null, null);

        assertThat(shown(result)).isEqualTo("one");
    }

    @Test
    void anEmptyResourceSaysSo() throws Exception {
        var read = tools(new FakeServer("files")).get(2);
        var result = read.agent().execute("id", Map.of("server", "files", "uri", "file:///a.txt"),
                null, null, null);
        assertThat(shown(result)).isEqualTo("Resource file:///a.txt is empty.");
    }

    @Test
    void readingRequiresBothArguments() {
        var read = tools(new FakeServer("files")).get(2);
        assertThatThrownBy(() -> read.agent().execute("id", Map.of("uri", "file:///a.txt"),
                null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("server must be provided");
        assertThatThrownBy(() -> read.agent().execute("id", Map.of("server", "files"),
                null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("uri must be provided");
    }

    @Test
    void aNonStringArgumentIsRejected() {
        assertThatThrownBy(() -> McpResourceTools.stringArgument(Map.of("server", 1), "server"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("server must be a string");
    }

    @Test
    void aBlankArgumentCountsAsMissing() {
        assertThat(McpResourceTools.stringArgument(Map.of("server", "  "), "server")).isNull();
        assertThat(McpResourceTools.stringArgument(Map.of("server", " files "), "server"))
                .isEqualTo("files");
        assertThat(McpResourceTools.stringArgument(Map.of(), "server")).isNull();
        assertThat(McpResourceTools.stringArgument(null, "server")).isNull();
    }

    @Test
    void allThreeToolsAreReadOnlyAndDirect() {
        for (var definition : tools(new FakeServer("files"))) {
            assertThat(definition.exposure()).isEqualTo(McpToolExposure.DIRECT);
            assertThat(definition.annotations()).isEqualTo(
                    new McpToolDefinition.Annotations(true, null, null, null));
            assertThat(definition.namespace()).isNull();
        }
        assertThat(tools(new FakeServer("f"))).extracting(McpToolDefinition::name)
                .containsExactly("list_mcp_resources", "list_mcp_resource_templates",
                        "read_mcp_resource");
    }
}
