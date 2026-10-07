package com.pijava.mcp.runtime;

import java.nio.file.Path;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.config.McpServerEntry;

/**
 * What one connection is built from (pi's constructor options object, {@code runtime.ts:183-195}).
 *
 * @param entry the configured server
 * @param cwd the directory the session runs in
 * @param createTransport builds the transport
 * @param credentials the OAuth store of {@code mcp-auth.json}
 * @param providerToken the current token of a pi provider, for servers with {@code auth.provider}
 * @param onTools called when the server's tools change
 * @param onChange called when {@code state}, {@code error}, or {@code tools} change
 * @param log receives the server's {@code notifications/message}
 * @param clientName the client name sent in {@code initialize}
 * @param clientVersion the client version sent in {@code initialize}
 * @param resolver resolves {@code $NAME} and {@code !cmd} configuration values
 */
public record McpConnectionOptions(
        McpServerEntry entry,
        Path cwd,
        McpTransportFactory createTransport,
        McpOAuthCredentialStore credentials,
        @Nullable McpProviderToken providerToken,
        Consumer<McpServerConnection> onTools,
        @Nullable Consumer<McpServerConnection> onChange,
        @Nullable McpServerLog log,
        String clientName,
        String clientVersion,
        ConfigValueResolver resolver) {

    /**
     * The essentials, with pi's defaults for everything else: the default transport factory, a
     * process-wide config value resolver, and no host callbacks.
     *
     * @param entry the configured server
     * @param cwd the directory the session runs in
     * @param credentials the OAuth store
     * @param onTools called when the server's tools change
     * @param clientName the client name sent in {@code initialize}
     * @param clientVersion the client version sent in {@code initialize}
     * @return the options
     */
    public static McpConnectionOptions of(McpServerEntry entry, Path cwd,
                                          McpOAuthCredentialStore credentials,
                                          Consumer<McpServerConnection> onTools,
                                          String clientName, String clientVersion) {
        var resolver = ConfigValueResolver.DEFAULT;
        return new McpConnectionOptions(entry, cwd,
                (server, directory, provider) -> McpDefaultTransport.create(server, directory, provider, resolver),
                credentials, null, onTools, null, null, clientName, clientVersion, resolver);
    }
}
