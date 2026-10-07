package com.pijava.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.protocol.McpTool;
import com.pijava.mcp.protocol.ReadResourceResult;
import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;
import com.pijava.mcp.protocol.content.CallToolResult;

/**
 * Typed request shortcuts of {@link McpClient} (client.ts:290-384).
 */
final class McpClientShortcuts {

    private McpClientShortcuts() {
    }

    /** Configured roots (fixed or supplied), or an empty list (client.ts:175). */
    static List<com.pijava.mcp.protocol.Root> roots(McpClientOptions options) {
        var roots = options.roots();
        if (roots == null) {
            return List.of();
        }
        return switch (roots) {
            case McpClientOptions.Roots.Fixed fixed -> fixed.roots();
            case McpClientOptions.Roots.Supplied supplied -> supplied.supplier().get();
        };
    }

    /** Ping the server (client.ts:290-292). */
    static CompletableFuture<Void> ping(McpClient client, McpRequestOptions options) {
        return client.request("ping", null, options).thenApply(ignore -> null);
    }

    /** List every tool, through all pages (client.ts:294-296). */
    static CompletableFuture<List<McpTool>> listTools(
            McpClient client, McpRequestOptions options) {
        return McpClientPages.listAll(client, "tools/list", "tools",
                        McpClientValidators::isTool, options)
                .thenApply(items -> items.stream()
                        .map(raw -> McpJson.mapper().convertValue(raw, McpTool.class))
                        .toList());
    }

    /** Every resource (client.ts:298-301). */
    static CompletableFuture<List<Resource>> listResources(
            McpClient client, McpRequestOptions options) {
        return McpClientPages.listAll(client, "resources/list", "resources",
                        McpClientValidators::isResource, options)
                .thenApply(items -> items.stream().map(McpClientWires::toResource).toList());
    }

    /** One page of resources (client.ts:303-308). */
    static CompletableFuture<com.pijava.mcp.protocol.ListResourcesResult> listResourcesPage(
            McpClient client, @Nullable String cursor, McpRequestOptions options) {
        return client.request("resources/list",
                        cursor == null ? null : Map.of("cursor", cursor), options)
                .thenApply(raw -> {
                    var page = McpClientValidators.validateListPage(
                            "resources/list", "resources", raw, McpClientValidators::isResource);
                    var resources = page.items().stream().map(McpClientWires::toResource).toList();
                    return new com.pijava.mcp.protocol.ListResourcesResult(
                            resources, page.nextCursor(), null);
                });
    }

    /** Every resource template (client.ts:309-318). */
    static CompletableFuture<List<ResourceTemplate>> listResourceTemplates(
            McpClient client, McpRequestOptions options) {
        return McpClientPages.listAll(client, "resources/templates/list", "resourceTemplates",
                        McpClientValidators::isResourceTemplate, options)
                .thenApply(items -> items.stream().map(McpClientWires::toResourceTemplate).toList());
    }

    /** One page of resource templates (client.ts:320-333). */
    static CompletableFuture<com.pijava.mcp.protocol.ListResourceTemplatesResult>
            listResourceTemplatesPage(McpClient client, @Nullable String cursor,
                                      McpRequestOptions options) {
        return client.request("resources/templates/list",
                        cursor == null ? null : Map.of("cursor", cursor), options)
                .thenApply(raw -> {
                    var page = McpClientValidators.validateListPage(
                            "resources/templates/list", "resourceTemplates", raw,
                            McpClientValidators::isResourceTemplate);
                    var templates = page.items().stream()
                            .map(McpClientWires::toResourceTemplate).toList();
                    return new com.pijava.mcp.protocol.ListResourceTemplatesResult(
                            templates, page.nextCursor(), null);
                });
    }

    /** Read one resource (client.ts:335-337). */
    static CompletableFuture<ReadResourceResult> readResource(
            McpClient client, String uri, McpRequestOptions options) {
        return client.request("resources/read", Map.of("uri", uri), options)
                .thenApply(McpClientValidators::validateReadResourceResult);
    }

    /** Call one tool (client.ts:376-384). */
    static CompletableFuture<CallToolResult> callTool(
            McpClient client, String name, @Nullable Map<String, Object> args,
            McpRequestOptions options) {
        var params = new LinkedHashMap<String, Object>();
        params.put("name", name);
        if (args != null) {
            params.put("arguments", args);
        }
        return client.request("tools/call", params, options)
                .thenApply(McpClientValidators::validateCallToolResult);
    }
}
