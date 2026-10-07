package com.pijava.mcp.runtime;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.protocol.ListResourceTemplatesResult;
import com.pijava.mcp.protocol.ListResourcesResult;
import com.pijava.mcp.protocol.ReadResourceResult;
import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;

/**
 * A connected server that offers resources (pi {@code resources.ts:39-47}), which the resource
 * tools of the next package hold.
 */
public interface McpResourceServer {

    /** The server name. */
    String name();

    /** Per-request timeout in ms. */
    long timeoutMs();

    /**
     * One page of {@code resources/list}.
     *
     * @param cursor the cursor from the previous page, or {@code null}
     * @param options per-request options
     * @return the page
     */
    CompletableFuture<ListResourcesResult> resourcesPage(@Nullable String cursor, McpRequestOptions options);

    /**
     * One page of {@code resources/templates/list}.
     *
     * @param cursor the cursor from the previous page, or {@code null}
     * @param options per-request options
     * @return the page; empty when the server does not implement the method
     */
    CompletableFuture<ListResourceTemplatesResult> resourceTemplatesPage(@Nullable String cursor,
                                                                        McpRequestOptions options);

    /**
     * Every listed resource.
     *
     * @param options per-request options
     * @return the resources
     */
    CompletableFuture<List<Resource>> allResources(McpRequestOptions options);

    /**
     * Every listed resource template.
     *
     * @param options per-request options
     * @return the templates; empty when the server does not implement the method
     */
    CompletableFuture<List<ResourceTemplate>> allResourceTemplates(McpRequestOptions options);

    /**
     * Read one resource.
     *
     * @param uri the resource URI
     * @param options per-request options
     * @return the contents
     */
    CompletableFuture<ReadResourceResult> readResource(String uri, McpRequestOptions options);
}
