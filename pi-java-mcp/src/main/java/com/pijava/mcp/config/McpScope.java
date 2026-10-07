package com.pijava.mcp.config;

/**
 * Which file defined a server entry ({@code config.ts:60}).
 */
public enum McpScope {

    /** The global {@code mcp.json} in the agent directory. */
    GLOBAL,

    /** The project's {@code mcp.json}. */
    PROJECT,

    /**
     * Registered by an extension with {@code pi.registerMcpServer()}
     * ({@code mcp-servers.ts:281-286}); the entry's source is the extension's path.
     */
    EXTENSION
}
