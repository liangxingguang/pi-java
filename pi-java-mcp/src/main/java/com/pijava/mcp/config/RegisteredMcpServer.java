package com.pijava.mcp.config;

/**
 * A server an extension registered ({@code mcp-servers.ts:281-286}).
 *
 * @param name the server name
 * @param config the server's configuration
 * @param extensionPath path of the extension that registered it, which owns it
 */
public record RegisteredMcpServer(String name, McpServerConfig config, String extensionPath) {
}
