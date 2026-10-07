package com.pijava.coding.agent.extension.mcp;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.config.McpServerEntry;

/**
 * What the {@code mcp_servers} section needs of a server
 * (pi {@code McpServerListing}, {@code index.ts:174-178}).
 *
 * @param entry the configured server
 * @param instructions the server's own instructions once connected, when it sent any
 */
public record McpServerListing(McpServerEntry entry, @Nullable String instructions) {

    /** A server that has not connected, so it has no instructions yet. */
    public McpServerListing(McpServerEntry entry) {
        this(entry, null);
    }
}
