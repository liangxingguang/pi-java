package com.pijava.coding.agent.extension.mcp;

import org.jspecify.annotations.Nullable;

/**
 * Details of one MCP tool result (pi {@code McpToolDetails}, {@code tools.ts:58-63}).
 *
 * @param server the MCP server the result came from
 * @param tool the tool name as the server offers it
 * @param fullOutputPath the file holding the full text, when the model-facing text was truncated
 */
public record McpToolDetails(String server, String tool, @Nullable String fullOutputPath) {

    /**
     * Details without a saved file.
     *
     * @param server the MCP server the result came from
     * @param tool the tool name as the server offers it
     * @return the details
     */
    public static McpToolDetails of(String server, String tool) {
        return new McpToolDetails(server, tool, null);
    }
}
