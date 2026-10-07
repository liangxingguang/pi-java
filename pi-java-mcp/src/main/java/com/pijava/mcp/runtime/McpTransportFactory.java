package com.pijava.mcp.runtime;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.AuthProvider;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.transport.McpTransport;

/**
 * Builds the transport of one configured server (pi {@code McpTransportFactory},
 * {@code runtime.ts:58-62}).
 */
@FunctionalInterface
public interface McpTransportFactory {

    /**
     * Build the transport.
     *
     * @param entry the configured server
     * @param cwd the directory the session runs in
     * @param authProvider the credential provider, or {@code null}
     * @return the transport, not yet started
     */
    McpTransport create(McpServerEntry entry, Path cwd, @Nullable AuthProvider authProvider);
}
