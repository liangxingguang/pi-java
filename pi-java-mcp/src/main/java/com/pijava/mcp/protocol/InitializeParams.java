package com.pijava.mcp.protocol;

/**
 * Params of the {@code initialize} request ({@code types.ts:39-43}).
 *
 * @param protocolVersion requested protocol version
 * @param capabilities    client capabilities
 * @param clientInfo      client implementation info
 */
public record InitializeParams(String protocolVersion, ClientCapabilities capabilities,
                               Implementation clientInfo) {
}
