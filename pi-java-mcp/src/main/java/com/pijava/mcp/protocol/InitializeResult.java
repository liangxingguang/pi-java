package com.pijava.mcp.protocol;

import org.jspecify.annotations.Nullable;

/**
 * Result of the {@code initialize} request ({@code types.ts:45-50}).
 *
 * @param protocolVersion protocol version the server selected
 * @param capabilities    server capabilities
 * @param serverInfo      server implementation info
 * @param instructions    optional usage instructions
 */
public record InitializeResult(String protocolVersion, ServerCapabilities capabilities,
                               Implementation serverInfo, @Nullable String instructions) {
}
