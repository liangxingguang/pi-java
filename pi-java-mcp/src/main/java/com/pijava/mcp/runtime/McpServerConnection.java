package com.pijava.mcp.runtime;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpClient;
import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.oauth.McpOAuthAuthorizationRequiredError;
import com.pijava.mcp.oauth.OAuthChallenge;
import com.pijava.mcp.protocol.ListResourceTemplatesResult;
import com.pijava.mcp.protocol.ListResourcesResult;
import com.pijava.mcp.protocol.McpTool;
import com.pijava.mcp.protocol.ReadResourceResult;
import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;
import com.pijava.mcp.protocol.content.CallToolResult;
import com.pijava.mcp.transport.http.McpAuthRequiredError;
import com.pijava.mcp.transport.http.McpHttpError;
import com.pijava.mcp.transport.http.McpSessionExpiredError;

/**
 * One configured server: reconnects lazily when a call finds the connection gone
 * (pi {@code McpServerConnection}, {@code runtime.ts:154-474}).
 *
 * <p>Opening and watching the client lives in {@link McpConnector}; the mutable connection
 * state is visible to this package so that half can maintain it.</p>
 */
public final class McpServerConnection implements McpToolCaller, McpResourceServer {

    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    final McpConnectionOptions options;
    final McpServerEntry entry;
    final Path cwd;
    final @Nullable McpAuthProvider authProvider;

    volatile McpServerState state = McpServerState.CONNECTING;
    volatile @Nullable String error;
    volatile List<McpTool> tools = List.of();
    volatile boolean hasResources;
    volatile List<Resource> resources = List.of();
    volatile List<ResourceTemplate> resourceTemplates = List.of();
    volatile @Nullable String instructions;
    volatile @Nullable OAuthChallenge challenge;

    private final Object lock = new Object();
    @Nullable McpClient client;
    @Nullable CompletableFuture<McpClient> opening;
    volatile boolean closed;
    @Nullable String stderrTail;

    /**
     * Create a connection.
     *
     * @param options what to connect to and how
     */
    public McpServerConnection(McpConnectionOptions options) {
        this.options = options;
        this.entry = options.entry();
        this.cwd = options.cwd();
        var url = oauthUrl();
        var provider = entry.config() instanceof McpServerConfig.Http http && http.auth() != null
                ? http.auth().provider() : null;
        if (url != null) {
            this.authProvider = McpAuthProviders.create(new McpAuthProviders.Options(
                    URI.create(url), options.credentials().forServer(entry.name(), url),
                    this::oauthSettings, value -> this.challenge = value, options.clientName()));
        } else if (provider != null) {
            this.authProvider = new McpConnectionSupport.ReadThroughProvider(
                    provider, options.providerToken());
        } else {
            this.authProvider = null;
        }
    }

    /** The configured server. */
    public McpServerEntry entry() {
        return entry;
    }

    @Override
    public String name() {
        return entry.name();
    }

    /** Where the connection stands. */
    public McpServerState state() {
        return state;
    }

    /** Why the last attempt failed, or {@code null}. */
    public @Nullable String error() {
        return error;
    }

    /** The tools the server listed at the last connect or change. */
    public List<McpTool> tools() {
        return tools;
    }

    /** Whether the server offers resources. */
    public boolean hasResources() {
        return hasResources;
    }

    /** The resources listed at the last connect or change, without MCP App resources. */
    public List<Resource> resources() {
        return resources;
    }

    /** The resource templates listed at the last connect or change. */
    public List<ResourceTemplate> resourceTemplates() {
        return resourceTemplates;
    }

    /** Server instructions from {@code initialize}, describing its tools as a group. */
    public @Nullable String instructions() {
        return instructions;
    }

    /** The last OAuth challenge; sign-in uses its resource metadata URL and scope. */
    public @Nullable OAuthChallenge challenge() {
        return challenge;
    }

    /** Forget the last challenge, once the sign-in that used it has finished. */
    public void clearChallenge() {
        this.challenge = null;
    }

    @Override
    public long timeoutMs() {
        var timeout = entry.config().timeout();
        return (timeout == null ? DEFAULT_TIMEOUT_SECONDS : timeout.longValue()) * 1000L;
    }

    /** The server URL when the server authenticates with OAuth. */
    public @Nullable String oauthUrl() {
        return McpConnectionSupport.usesOAuth(entry) && entry.config() instanceof McpServerConfig.Http http
                ? http.url() : null;
    }

    /**
     * The OAuth settings of this server, with the client secret resolved
     * ({@code runtime.ts:232-248}). pi exposes it, and the sign-in command uses it.
     *
     * @return the settings
     */
    public McpOAuthSettings oauthSettings() {
        var oauth = entry.config() instanceof McpServerConfig.Http http ? http.oauth() : null;
        if (oauth == null) {
            return McpOAuthSettings.none();
        }
        return new McpOAuthSettings(
                oauth.clientId(),
                oauth.clientSecret() == null ? null : options.resolver().resolveOrThrow(
                        oauth.clientSecret(), "MCP server \"" + entry.name() + "\" oauth.clientSecret", null),
                oauth.callbackPort(),
                oauth.callbackUrl(),
                oauth.scope(),
                oauth.clientName(),
                McpOAuthSettings.registrationOf(oauth.clientRegistration()),
                oauth.authServerMetadataUrl() == null ? null : URI.create(oauth.authServerMetadataUrl()));
    }

    // ------------------------------------------------------------------ calls

    /**
     * Connect if needed.
     *
     * @return the client
     */
    public CompletableFuture<McpClient> getClient() {
        if (closed) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("MCP server \"" + entry.name() + "\" is shut down"));
        }
        var connected = client;
        if (connected != null && "connected".equals(connected.connectionState())) {
            return CompletableFuture.completedFuture(connected);
        }
        synchronized (lock) {
            if (opening == null) {
                var started = new CompletableFuture<McpClient>();
                opening = started;
                Thread.startVirtualThread(() -> {
                    try {
                        started.complete(McpConnector.open(this));
                    } catch (Throwable failure) {
                        started.completeExceptionally(failure);
                    } finally {
                        synchronized (lock) {
                            if (opening == started) {
                                opening = null;
                            }
                        }
                    }
                });
            }
            return opening;
        }
    }

    @Override
    public CompletableFuture<CallToolResult> callTool(String name, Map<String, Object> args,
                                                      McpRequestOptions requestOptions) {
        // Tool calls are not retried: they may have run (runtime.ts:288-290).
        return withClient(client -> client.callTool(name, args, requestOptions), false);
    }

    @Override
    public CompletableFuture<ReadResourceResult> readResource(String uri, McpRequestOptions requestOptions) {
        return withClient(client -> client.readResource(uri, requestOptions), true);
    }

    @Override
    public CompletableFuture<ListResourcesResult> resourcesPage(@Nullable String cursor,
                                                                McpRequestOptions requestOptions) {
        return withClient(client -> client.listResourcesPage(cursor, requestOptions), true);
    }

    @Override
    public CompletableFuture<ListResourceTemplatesResult> resourceTemplatesPage(
            @Nullable String cursor, McpRequestOptions requestOptions) {
        return withClient(client -> McpConnectionSupport.withoutTemplates(
                () -> client.listResourceTemplatesPage(cursor, requestOptions),
                new ListResourceTemplatesResult(List.of(), null, null)), true);
    }

    @Override
    public CompletableFuture<List<Resource>> allResources(McpRequestOptions requestOptions) {
        return withClient(client -> client.listResources(requestOptions), true);
    }

    @Override
    public CompletableFuture<List<ResourceTemplate>> allResourceTemplates(McpRequestOptions requestOptions) {
        return withClient(client -> McpConnectionSupport.withoutTemplates(
                () -> client.listResourceTemplates(requestOptions), List.of()), true);
    }

    /**
     * Connect again with fresh credentials, for example after signing in.
     *
     * @return when the connection is back
     */
    public CompletableFuture<Void> reconnect() {
        return McpConnectionSupport.onWorker(() -> {
            McpConnectionSupport.awaitQuietly(opening);
            var current = client;
            if (current != null) {
                dropClient(current);
            }
            getClient().get();
        });
    }

    /**
     * Disconnect after the stored credentials were removed.
     *
     * @return when the connection is gone
     */
    public CompletableFuture<Void> signOut() {
        return McpConnectionSupport.onWorker(() -> {
            McpConnectionSupport.awaitQuietly(opening);
            var current = client;
            if (current != null) {
                dropClient(current);
            }
            if (!closed) {
                markNeedsAuth();
            }
        });
    }

    /**
     * Close the connection for good.
     *
     * @return when the transport is closed and any refresh has settled
     */
    public CompletableFuture<Void> close() {
        closed = true;
        state = McpServerState.CLOSED;
        changed();
        var current = client;
        client = null;
        return McpConnectionSupport.onWorker(() -> {
            if (current != null) {
                closeQuietly(current);
            }
            // A refresh the server already answered may have rotated the refresh token; exiting
            // before the new tokens are saved would lose the grant (runtime.ts:470-472).
            if (authProvider != null) {
                try {
                    authProvider.settled();
                } catch (Exception ignored) {
                    // Shutting down anyway.
                }
            }
        });
    }

    // ----------------------------------------------------------------- internals

    /** Run a request, reconnecting when needed ({@code runtime.ts:287-314}). */
    private <T> CompletableFuture<T> withClient(Function<McpClient, CompletableFuture<T>> run,
                                                boolean readOnly) {
        return McpConnectionSupport.onWorker(() -> withClientBlocking(run, readOnly));
    }

    private <T> T withClientBlocking(Function<McpClient, CompletableFuture<T>> run, boolean readOnly)
            throws Exception {
        for (var attempt = 1; ; attempt++) {
            var client = getClient().get();
            try {
                return run.apply(client).get();
            } catch (ExecutionException wrapped) {
                var failure = McpConnectionSupport.unwrap(wrapped);
                if (readOnly && attempt == 1 && failure instanceof McpHttpError http
                        && McpConnectionSupport.isTransientError(http)) {
                    Thread.sleep(250);
                    continue;
                }
                if (failure instanceof McpSessionExpiredError && attempt == 1) {
                    // The server no longer knows the session (restart, deploy), so it did not run
                    // the request. Retry once on a new session. The old client is detached but not
                    // closed: closing would fail its other in-flight calls, which instead get the
                    // same 404 and retry the same way (runtime.ts:301-307).
                    if (this.client == client) {
                        this.client = null;
                    }
                    continue;
                }
                if (!needsSignIn(failure)) {
                    throw McpConnectionSupport.asException(failure);
                }
                dropClient(client);
                markNeedsAuth();
                throw new IllegalStateException(signInRequiredMessage());
            }
        }
    }

    /** OAuth servers that still reject the request after a refresh need the user to sign in. */
    boolean needsSignIn(Throwable failure) {
        return failure instanceof McpOAuthAuthorizationRequiredError
                || (authProvider != null && failure instanceof McpAuthRequiredError);
    }

    void markNeedsAuth() {
        state = McpServerState.NEEDS_AUTH;
        error = null;
        changed();
    }

    void changed() {
        var onChange = options.onChange();
        if (onChange != null) {
            onChange.accept(this);
        }
    }

    String signInRequiredMessage() {
        return McpConnectionSupport.signInRequiredMessage(entry);
    }

    private void dropClient(McpClient client) {
        if (this.client == client) {
            this.client = null;
        }
        closeQuietly(client);
    }

    private static void closeQuietly(McpClient client) {
        try {
            client.close().get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException ignored) {
            // Detaching is what matters.
        }
    }
}
