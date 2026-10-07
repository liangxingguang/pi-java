package com.pijava.mcp.runtime;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.protocol.content.CallToolResult;

/**
 * The tool-calling face of a connected server (pi {@code tools.ts:78-80}), which the tool
 * adapters of the next package hold.
 */
@FunctionalInterface
public interface McpToolCaller {

    /**
     * Call one tool.
     *
     * @param name the tool name as the server offers it
     * @param args the tool arguments
     * @param options per-request options
     * @return the tool result
     */
    CompletableFuture<CallToolResult> callTool(String name, Map<String, Object> args,
                                               McpRequestOptions options);
}
