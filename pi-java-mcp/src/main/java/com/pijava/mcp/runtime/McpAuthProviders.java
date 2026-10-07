package com.pijava.mcp.runtime;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.JdkMcpFetch;
import com.pijava.mcp.McpFetch;
import com.pijava.mcp.oauth.McpOAuthAuthorizationRequiredError;
import com.pijava.mcp.oauth.McpOAuthProvider;
import com.pijava.mcp.oauth.McpOAuthProviderOptions;
import com.pijava.mcp.oauth.McpOAuthState;
import com.pijava.mcp.oauth.OAuthChallenge;
import com.pijava.mcp.oauth.OAuthClientInformation;
import com.pijava.mcp.oauth.OAuthClientMetadata;
import com.pijava.mcp.oauth.OAuthFlow;
import com.pijava.mcp.oauth.OAuthFlowOptions;
import com.pijava.mcp.oauth.OAuthFlowResult;
import com.pijava.mcp.oauth.OAuthStateStore;
import com.pijava.mcp.oauth.WwwAuthenticate;

/**
 * Auth provider for MCP connections: sends the stored access token and refreshes it when it is
 * about to expire or after a 401 (pi {@code createMcpAuthProvider}, {@code oauth.ts:289-363}).
 *
 * <p>Throws {@link McpOAuthAuthorizationRequiredError} when the user has to sign in, including
 * when the server asks for more scope ({@code insufficient_scope}). {@code onChallenge} receives
 * the server's {@code WWW-Authenticate} challenge so sign-in can use its resource metadata URL
 * and scope.</p>
 *
 * <p>{@code settings} is only called when a refresh is needed, so a secret that fails to resolve
 * fails the refresh instead of the whole connection setup.</p>
 *
 * <p>Many servers rotate refresh tokens, so two refreshes with the same refresh token lose the
 * grant. Requests in this process share one refresh, and other processes are kept out by the
 * store's refresh lock, held from reading the tokens to saving new ones. Tokens that changed
 * meanwhile — another process refreshed them, or the user signed in — are used without
 * refreshing.</p>
 */
public final class McpAuthProviders {

    /** Access tokens this close to expiry are refreshed before they are sent ({@code oauth.ts:46}). */
    private static final long REFRESH_SKEW_MS = 30_000;

    /** Bounds each request of a refresh ({@code oauth.ts:48}). */
    private static final long REFRESH_REQUEST_TIMEOUT_MS = 15_000;

    private McpAuthProviders() {
    }

    /**
     * What an auth provider is built from (pi's options object, {@code oauth.ts:302-307}).
     *
     * @param serverUrl the MCP server URL
     * @param store the server's slice of {@code mcp-auth.json}
     * @param settings the OAuth configuration, evaluated lazily on each refresh
     * @param onChallenge receives the server's challenge
     * @param clientName the application name, the {@code client_name} fallback for registration
     */
    public record Options(
            URI serverUrl,
            McpOAuthServerStore store,
            Supplier<McpOAuthSettings> settings,
            Consumer<OAuthChallenge> onChallenge,
            String clientName) {
    }

    /**
     * Build the provider.
     *
     * @param options what to build it from
     * @return the provider
     */
    public static McpAuthProvider create(Options options) {
        return new Provider(options);
    }

    /** The stateful provider the flow drives ({@code oauth.ts:262-282}). */
    static McpOAuthProvider createProvider(
            String serverUrl,
            OAuthStateStore store,
            McpOAuthSettings settings,
            String redirectUrl,
            String clientName,
            McpOAuthProviderOptions.OnRedirect onRedirect) {
        var metadata = new OAuthClientMetadata(List.of(), null, null, null,
                settings.clientName() != null ? settings.clientName() : clientName,
                null, null, null, null, null, null, null, null, null, null, null);
        return new McpOAuthProvider(new McpOAuthProviderOptions(
                URI.create(serverUrl),
                URI.create(redirectUrl),
                metadata,
                settings.clientRegistration() == McpOAuthSettings.Registration.CIMD
                        ? value -> McpClientMetadataDocuments.create(serverUrl, redirectUrl, value)
                        : null,
                settings.clientId(),
                settings.clientSecret(),
                store,
                onRedirect));
    }

    /** The first redirect URI a registration response echoed back ({@code oauth.ts:223-225}). */
    static @Nullable String firstRegisteredRedirectUrl(@Nullable OAuthClientInformation information) {
        if (information == null || information.redirectUris() == null || information.redirectUris().isEmpty()) {
            return null;
        }
        return information.redirectUris().get(0);
    }

    private static @Nullable String accessToken(@Nullable McpOAuthState state) {
        return state == null || state.tokens() == null ? null : state.tokens().accessToken();
    }

    private static @Nullable String refreshToken(@Nullable McpOAuthState state) {
        return state == null || state.tokens() == null ? null : state.tokens().refreshToken();
    }

    private static boolean expired(@Nullable McpOAuthState state) {
        return state != null && state.tokensExpireAt() != null
                && state.tokensExpireAt() - REFRESH_SKEW_MS <= System.currentTimeMillis();
    }

    /** Wait for a promise the way pi's {@code await promise.catch(() => undefined)} does. */
    private static void awaitQuietly(@Nullable CompletableFuture<Void> future) {
        if (future == null) {
            return;
        }
        try {
            future.join();
        } catch (CancellationException | CompletionException ignored) {
            // pi swallows a failed refresh here and inspects the stored tokens instead.
        }
    }

    /** Wait for a promise, rethrowing what it failed with. */
    private static void await(CompletableFuture<Void> future) throws Exception {
        try {
            future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw error;
        }
    }

    private static final class Provider implements McpAuthProvider {

        private final Options options;
        private final Object lock = new Object();
        private @Nullable CompletableFuture<Void> refreshing;

        private Provider(Options options) {
            this.options = options;
        }

        @Override
        public @Nullable String token() {
            awaitQuietly(currentRefresh());
            var state = options.store().load();
            var token = accessToken(state);
            if (!expired(state) || refreshToken(state) == null) {
                return token;
            }
            // Failures fall through: the request goes out with the old token and a 401 decides.
            awaitQuietly(refresh(token, null, null));
            return accessToken(options.store().load());
        }

        @Override
        public void onUnauthorized(Context context) throws Exception {
            var challenge = WwwAuthenticate.parse(context.wwwAuthenticate());
            options.onChallenge().accept(challenge);
            // A refresh keeps the granted scope, so more scope needs a new sign-in.
            if ("insufficient_scope".equals(challenge.error())) {
                throw new McpOAuthAuthorizationRequiredError();
            }
            await(refresh(context.token(), context.fetch(), challenge));
        }

        @Override
        public void settled() {
            awaitQuietly(currentRefresh());
        }

        private @Nullable CompletableFuture<Void> currentRefresh() {
            synchronized (lock) {
                return refreshing;
            }
        }

        /** Start a refresh, or join the one already running ({@code oauth.ts:309-339}). */
        private CompletableFuture<Void> refresh(
                @Nullable String staleToken, @Nullable McpFetch fetch, @Nullable OAuthChallenge challenge) {
            synchronized (lock) {
                if (refreshing != null) {
                    return refreshing;
                }
                var started = new CompletableFuture<Void>();
                refreshing = started;
                Thread.startVirtualThread(() -> {
                    try {
                        runRefresh(staleToken, fetch, challenge);
                        started.complete(null);
                    } catch (Throwable error) {
                        started.completeExceptionally(error);
                    } finally {
                        synchronized (lock) {
                            if (refreshing == started) {
                                refreshing = null;
                            }
                        }
                    }
                });
                return started;
            }
        }

        private void runRefresh(
                @Nullable String staleToken, @Nullable McpFetch fetch, @Nullable OAuthChallenge challenge)
                throws Exception {
            options.store().withRefreshLock(() -> {
                var state = options.store().load();
                if (!Objects.equals(accessToken(state), staleToken)) {
                    return null;
                }
                if (refreshToken(state) == null) {
                    throw new McpOAuthAuthorizationRequiredError();
                }
                var settings = options.settings().get();
                var redirectUrl = McpCallbackSettings.from(settings).fixedRedirectUrl();
                if (redirectUrl == null) {
                    redirectUrl = firstRegisteredRedirectUrl(
                            state == null ? null : state.clientInformation());
                }
                if (redirectUrl == null) {
                    redirectUrl = McpCallbackSettings.FALLBACK_REDIRECT_URL;
                }
                var serverUrl = options.serverUrl().toString();
                var provider = createProvider(serverUrl, options.store(), settings, redirectUrl,
                        options.clientName(), url -> {
                        });
                var flowOptions = new OAuthFlowOptions(
                        options.serverUrl(),
                        null,
                        null,
                        challenge == null ? null : challenge.scope(),
                        challenge == null ? null : challenge.resourceMetadataUrl(),
                        settings.authServerMetadataUrl(),
                        new TimedMcpFetch(fetch != null ? fetch : new JdkMcpFetch(),
                                REFRESH_REQUEST_TIMEOUT_MS),
                        false,
                        false);
                if (OAuthFlow.authorizeMcp(provider, flowOptions) == OAuthFlowResult.REDIRECT) {
                    throw new McpOAuthAuthorizationRequiredError();
                }
                return null;
            });
        }
    }
}
