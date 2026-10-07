package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.mcp.McpJson;
import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.runtime.McpAppResources;
import com.pijava.mcp.runtime.McpResourceServer;

/**
 * MCP resources, through the tools Codex and opencode use (pi {@code createMcpResourceToolDefinitions},
 * {@code resources.ts:194-342}).
 *
 * <p>They take a {@code server} argument and cover every connected server with resources, so
 * models trained on those tools use them unchanged. Listings are JSON, as in Codex:
 * {@code { server?, resources: [{ server, ...resource }], nextCursor? }}. With a {@code server},
 * one page is listed and {@code cursor} continues it; without, every page of every server.</p>
 */
public final class McpResourceTools {

    /** Lists resources ({@code resources.ts:34}). */
    public static final String LIST_MCP_RESOURCES_TOOL = "list_mcp_resources";

    /** Lists resource templates ({@code resources.ts:35}). */
    public static final String LIST_MCP_RESOURCE_TEMPLATES_TOOL = "list_mcp_resource_templates";

    /** Reads one resource ({@code resources.ts:36}). */
    public static final String READ_MCP_RESOURCE_TOOL = McpResultContent.READ_MCP_RESOURCE_TOOL;

    private McpResourceTools() {
    }

    /**
     * What the three tools are built from (pi's options object, {@code resources.ts:197-200}).
     *
     * @param exposure the exposure to give them
     * @param servers the servers whose resources they reach, read at call time
     */
    public record Options(McpExposure exposure, Supplier<List<McpResourceServer>> servers) {
    }

    /**
     * The three resource tools.
     *
     * @param options what they are built from
     * @return list, list-templates, read, in that order
     */
    public static List<McpToolDefinition> create(Options options) {
        return List.of(
                listResources(options),
                listTemplates(options),
                readResource(options));
    }

    // ------------------------------------------------------------------ the tools

    private static McpToolDefinition listResources(Options options) {
        return tool(options, LIST_MCP_RESOURCES_TOOL,
                "Lists resources provided by MCP servers. Resources allow servers to share data"
                        + " that provides context to language models, such as files, database"
                        + " schemas, or application-specific information. Prefer resources over"
                        + " web search when possible.",
                McpResourceSchemas.LIST_PARAMETERS, McpResourceSchemas.LIST_OUTPUT_SCHEMA,
                (params, signal) -> jsonResult(options, LIST_MCP_RESOURCES_TOOL,
                        stringArgument(params, "server"),
                        list(options, params, signal, "resources",
                                (server, cursor, request) -> {
                                    var page = server.resourcesPage(cursor, request).get();
                                    return new Page(visible(server.name(), page.resources()),
                                            page.nextCursor());
                                },
                                (server, request) ->
                                        visible(server.name(), server.allResources(request).get()))));
    }

    private static McpToolDefinition listTemplates(Options options) {
        return tool(options, LIST_MCP_RESOURCE_TEMPLATES_TOOL,
                "Lists resource templates provided by MCP servers. Parameterized resource"
                        + " templates allow servers to share data that takes parameters and"
                        + " provides context to language models, such as files, database schemas,"
                        + " or application-specific information. Prefer resource templates over"
                        + " web search when possible.",
                McpResourceSchemas.LIST_PARAMETERS, McpResourceSchemas.LIST_TEMPLATES_OUTPUT_SCHEMA,
                (params, signal) -> jsonResult(options, LIST_MCP_RESOURCE_TEMPLATES_TOOL,
                        stringArgument(params, "server"),
                        list(options, params, signal, "resourceTemplates",
                                (server, cursor, request) -> {
                                    var page = server.resourceTemplatesPage(cursor, request).get();
                                    return new Page(visible(server.name(), page.resourceTemplates()),
                                            page.nextCursor());
                                },
                                (server, request) ->
                                        visible(server.name(),
                                                server.allResourceTemplates(request).get()))));
    }

    private static McpToolDefinition readResource(Options options) {
        return tool(options, READ_MCP_RESOURCE_TOOL,
                "Read a specific resource from an MCP server given the server name and resource URI.",
                McpResourceSchemas.READ_PARAMETERS, McpResourceSchemas.READ_OUTPUT_SCHEMA,
                (params, signal) -> {
                    var serverName = stringArgument(params, "server");
                    var uri = stringArgument(params, "uri");
                    if (serverName == null) {
                        throw new IllegalStateException("server must be provided");
                    }
                    if (uri == null) {
                        throw new IllegalStateException("uri must be provided");
                    }
                    var server = findServer(options.servers().get(), serverName);
                    var result = server.readResource(uri,
                            new McpRequestOptions(signal, server.timeoutMs(), null)).get();
                    return readResult(server.name(), uri, result);
                });
    }

    private static ToolResult<McpToolDetails> readResult(
            String server, String uri, com.pijava.mcp.protocol.ReadResourceResult result) {
        var contents = result.contents() == null
                ? List.<com.pijava.mcp.protocol.content.ResourceContents>of() : result.contents();
        // Several contents (for example a directory) are labeled with their URIs.
        var blocks = new ArrayList<com.pijava.mcp.protocol.content.McpContentBlock>();
        for (var content : contents) {
            if (contents.size() > 1) {
                blocks.add(new com.pijava.mcp.protocol.content.McpContentBlock.Text(
                        uriOf(content) + ":", null, null));
            }
            blocks.add(new com.pijava.mcp.protocol.content.McpContentBlock.Embedded(content, null, null));
        }
        var converted = McpResultContent.toModelContent(server, blocks, McpResultContent.Options.defaults());
        var limited = McpResultContent.limit(
                converted.isEmpty() ? List.of(new ContentBlock.TextContent("Resource " + uri + " is empty."))
                        : converted,
                McpResultContent.Options.defaults());
        var stripped = new ArrayList<Object>();
        for (var content : contents) {
            stripped.add(stripMeta(content));
        }
        return new ToolResult<>(
                limited.content(),
                new McpToolDetails(server, READ_MCP_RESOURCE_TOOL, limited.fullOutputPath()),
                null, false, List.of(), false,
                Map.of("server", server, "uri", uri, "contents", stripped));
    }

    // ------------------------------------------------------------------- listing

    /** One page of one server, or every page of every server ({@code resources.ts:213-254}). */
    private static Map<String, Object> list(Options options, @Nullable Map<String, Object> params,
                                            @Nullable AbortSignal signal, String key,
                                            Pager pager, Lister all) throws Exception {
        var serverName = stringArgument(params, "server");
        var cursor = stringArgument(params, "cursor");
        if (serverName != null) {
            var server = findServer(options.servers().get(), serverName);
            var page = pager.page(server, cursor, new McpRequestOptions(signal, server.timeoutMs(), null));
            var out = new LinkedHashMap<String, Object>();
            out.put("server", server.name());
            out.put(key, page.items());
            if (page.nextCursor() != null) {
                out.put("nextCursor", page.nextCursor());
            }
            return out;
        }
        if (cursor != null) {
            throw new IllegalStateException("cursor can only be used when a server is specified");
        }
        var servers = options.servers().get().stream()
                .sorted(Comparator.comparing(McpResourceServer::name)).toList();
        var futures = new ArrayList<CompletableFuture<List<Map<String, Object>>>>();
        for (var server : servers) {
            var future = new CompletableFuture<List<Map<String, Object>>>();
            Thread.startVirtualThread(() -> {
                try {
                    future.complete(all.all(server,
                            new McpRequestOptions(signal, server.timeoutMs(), null)));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
            futures.add(future);
        }
        var items = new ArrayList<Map<String, Object>>();
        var errors = new ArrayList<Map<String, Object>>();
        for (var index = 0; index < servers.size(); index++) {
            try {
                items.addAll(futures.get(index).join());
            } catch (CompletionException failure) {
                errors.add(Map.of("server", servers.get(index).name(),
                        "error", errorMessage(unwrap(failure))));
            }
        }
        var out = new LinkedHashMap<String, Object>();
        out.put(key, items);
        if (!errors.isEmpty()) {
            out.put("errors", errors);
        }
        return out;
    }

    /** One page of one server. */
    @FunctionalInterface
    private interface Pager {

        /** The page, already filtered and tagged with its server. */
        Page page(McpResourceServer server, @Nullable String cursor, McpRequestOptions options)
                throws Exception;
    }

    /** Every page of one server. */
    @FunctionalInterface
    private interface Lister {

        /** Every item, already filtered and tagged with its server. */
        List<Map<String, Object>> all(McpResourceServer server, McpRequestOptions options)
                throws Exception;
    }

    /** One page of a listing ({@code resources.ts:222-225}). */
    private record Page(List<Map<String, Object>> items, @Nullable String nextCursor) {
    }

    /** A listed resource or template without {@code _meta} and icons, tagged with its server. */
    private static List<Map<String, Object>> visible(String server, List<?> items) {
        var out = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            if (isVisible(item)) {
                out.add(listed(server, item));
            }
        }
        return out;
    }

    private static boolean isVisible(Object item) {
        return switch (item) {
            case com.pijava.mcp.protocol.Resource resource -> !McpAppResources.isMcpAppResource(resource);
            case com.pijava.mcp.protocol.ResourceTemplate template ->
                    !McpAppResources.isMcpAppResource(template);
            default -> true;
        };
    }

    /** {@code resources.ts:56-59}: {@code {server, ...rest}}, without {@code _meta} and icons. */
    private static Map<String, Object> listed(String server, Object item) {
        var out = new LinkedHashMap<String, Object>();
        out.put("server", server);
        McpJson.mapper().valueToTree(item).properties().forEach(entry -> {
            if (!"_meta".equals(entry.getKey()) && !"icons".equals(entry.getKey())) {
                out.put(entry.getKey(), McpJson.mapper().convertValue(entry.getValue(), Object.class));
            }
        });
        return out;
    }

    /** The URI of a text or blob content ({@code resources.ts:321}). */
    private static String uriOf(com.pijava.mcp.protocol.content.ResourceContents content) {
        return switch (content) {
            case com.pijava.mcp.protocol.content.ResourceContents.Text text -> text.uri();
            case com.pijava.mcp.protocol.content.ResourceContents.Blob blob -> blob.uri();
        };
    }

    private static Object stripMeta(Object item) {
        var out = new LinkedHashMap<String, Object>();
        McpJson.mapper().valueToTree(item).properties().forEach(entry -> {
            if (!"_meta".equals(entry.getKey())) {
                out.put(entry.getKey(), McpJson.mapper().convertValue(entry.getValue(), Object.class));
            }
        });
        return out;
    }

    private static McpResourceServer findServer(List<McpResourceServer> servers, String name) {
        for (var server : servers) {
            if (server.name().equals(name)) {
                return server;
            }
        }
        var available = servers.stream().map(McpResourceServer::name)
                .reduce((left, right) -> left + ", " + right).orElse("");
        throw new IllegalStateException("MCP server \"" + name + "\" has no resources"
                + (available.isEmpty() ? "" : ". Servers with resources: " + available));
    }

    // -------------------------------------------------------------------- shared

    private static ToolResult<McpToolDetails> jsonResult(Options options, String tool,
                                                         @Nullable String server,
                                                         Map<String, Object> payload) {
        var limited = McpResultContent.limit(
                List.of(new ContentBlock.TextContent(json(payload))),
                McpResultContent.Options.defaults());
        return new ToolResult<>(
                limited.content(),
                new McpToolDetails(server == null ? "" : server, tool, limited.fullOutputPath()),
                null, false, List.of(), false, payload);
    }

    /** {@code JSON.stringify} of a payload the tool built, which is already a plain map. */
    private static String json(Map<String, Object> payload) {
        try {
            return McpJson.mapper().writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    /** {@code resources.ts:170-175}. */
    static @Nullable String stringArgument(@Nullable Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        var value = params.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalStateException(key + " must be a string");
        }
        var trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static String errorMessage(Throwable error) {
        return McpResultContent.errorMessage(error);
    }

    private static Throwable unwrap(Throwable error) {
        var current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Build one tool of the three ({@code resources.ts:256-339}). */
    private static McpToolDefinition tool(Options options, String name, String description,
                                          Map<String, Object> parameters,
                                          Map<String, Object> outputSchema, ToolBody body) {
        return new McpToolDefinition(
                name, name, description, parameters, outputSchema,
                McpToolExposure.of(options.exposure()), null,
                new McpToolDefinition.Annotations(true, null, null, null),
                new ResourceAgentTool(name, description, parameters, body));
    }

    /** What one call of a resource tool does. */
    @FunctionalInterface
    private interface ToolBody {

        /** The result for these arguments. */
        ToolResult<McpToolDetails> run(@Nullable Map<String, Object> params,
                                       @Nullable AbortSignal signal) throws Exception;
    }

    /** The runnable half of a resource tool. */
    private static final class ResourceAgentTool
            implements AgentTool<Map<String, Object>, McpToolDetails> {

        private final String name;
        private final String description;
        private final Map<String, Object> parameters;
        private final ToolBody body;

        private ResourceAgentTool(String name, String description, Map<String, Object> parameters,
                                  ToolBody body) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return parameters;
        }

        @Override
        public ExecutionMode executionMode() {
            // Read-only across servers, so it batches in parallel.
            return new ExecutionMode.Parallel();
        }

        @Override
        public ToolResult<McpToolDetails> execute(
                String toolCallId, Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<McpToolDetails> onUpdate, ToolContext context) throws Exception {
            return body.run(params, signal);
        }
    }
}
