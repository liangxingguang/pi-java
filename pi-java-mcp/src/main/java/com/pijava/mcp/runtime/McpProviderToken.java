package com.pijava.mcp.runtime;

import org.jspecify.annotations.Nullable;

/**
 * The current token of a pi provider, for servers configured with {@code auth.provider}
 * (pi {@code runtime.ts:188-190}).
 *
 * <p>It is read on every request, so the provider's own refreshes apply; the MCP store keeps no
 * copy.</p>
 */
@FunctionalInterface
public interface McpProviderToken {

    /**
     * Read the provider's current token.
     *
     * @param provider the provider name from the server's configuration
     * @return the token, or {@code null}
     * @throws Exception when the token could not be read
     */
    @Nullable String token(String provider) throws Exception;
}
