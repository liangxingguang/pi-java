package com.pijava.mcp.runtime;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.mcp.oauth.McpOAuthState;
import com.pijava.mcp.oauth.McpRefreshLockBusyError;
import com.pijava.mcp.oauth.OAuthCallback;
import com.pijava.mcp.oauth.OAuthChallenge;
import com.pijava.mcp.oauth.OAuthClientInformation;
import com.pijava.mcp.oauth.OAuthEndpoints;
import com.pijava.mcp.oauth.OAuthFlow;
import com.pijava.mcp.oauth.OAuthFlowOptions;
import com.pijava.mcp.oauth.OAuthFlowResult;

/**
 * Sign in to an MCP server: refresh the stored tokens with the stored client when possible,
 * otherwise run the browser authorization code flow (pi {@code sign-in.ts}).
 *
 * <p>The sign-in comes in two halves, so an application can complete it in another request with
 * the same state store: {@link #start} refreshes or prepares the browser step, returning
 * {@link McpSignInRedirect} as plain data, and {@link #complete} exchanges the authorization
 * code. {@link #signIn} runs both halves over a loopback callback server.</p>
 */
public final class McpSignIn {

    private McpSignIn() {
    }

    /**
     * What a sign-in is built from (pi {@code McpSignInOptions}, {@code sign-in.ts:24-38}).
     *
     * @param serverUrl the MCP server URL
     * @param store the server's slice of {@code mcp-auth.json}, with its refresh lock
     * @param settings the OAuth configuration
     * @param challenge the challenge that asked for the sign-in, when there was one
     * @param prompt how to talk to the user
     * @param clientName the application name, the {@code client_name} fallback for registration
     */
    public record Options(
            String serverUrl,
            McpOAuthServerStore store,
            McpOAuthSettings settings,
            @Nullable OAuthChallenge challenge,
            McpSignInPrompt prompt,
            String clientName) {
    }

    /**
     * Sign in over loopback, returning once the tokens are stored (pi {@code startMcpSignIn} and
     * {@code completeMcpSignIn} driven from one call).
     *
     * @param options what to sign in to and how
     * @throws Exception when the flow failed; {@link McpSignInCancelledError} when the user
     *                   gave up
     */
    public static void signIn(Options options) throws Exception {
        var callback = McpLoopbackSignIn.listen(options.serverUrl(), options.store(),
                options.settings(), options.clientName());
        try {
            var started = start(options, callback.redirectUrl(), null);
            if (!(started instanceof McpSignInRedirect authorization)) {
                return;
            }
            options.prompt().showAuthorizationUrl(authorization.authorizationUrl());
            var response = McpLoopbackSignIn.await(callback.server(), authorization,
                    options.prompt(), null);
            complete(options, callback.redirectUrl(), authorization, response);
        } finally {
            callback.server().close();
        }
    }

    /**
     * First half of a sign-in (pi {@code startMcpSignIn}, {@code sign-in.ts:76-82}). Without a
     * step-up ({@code insufficient_scope}), it first tries to refresh the stored tokens with the
     * stored client; otherwise it prepares the browser step: a fresh {@code state}, and a stored
     * client that cannot use {@code redirectUrl} is dropped with its tokens.
     *
     * <p>Pass the same {@code redirectUrl} to {@link #complete}. Aborting {@code signal} stops the
     * wait for the refresh lock, but a refresh already sent still saves its tokens.</p>
     *
     * @param options what to sign in to and how
     * @param redirectUrl the redirect URI to use; {@link McpLoopbackSignIn#listen} provides one
     * @param signal aborts the sign-in, or {@code null}
     * @return {@link McpSignInStart.Authorized}, or the {@link McpSignInRedirect} to show
     * @throws Exception when the flow failed; {@link McpSignInCancelledError} when the user
     *                   gave up
     */
    public static McpSignInStart start(Options options, String redirectUrl, @Nullable AbortSignal signal)
            throws Exception {
        abortIfRequested(signal);
        var stepUp = stepUp(options);
        // A refresh keeps the granted scope, so a server asking for more needs the browser step.
        if (!stepUp && refreshedBeforeSignIn(options, redirectUrl, signal)) {
            return new McpSignInStart.Authorized();
        }
        abortIfRequested(signal);
        return browserStep(options, redirectUrl, stepUp);
    }

    /**
     * Second half of a sign-in: exchange the authorization code of {@code response} and save the
     * tokens (pi {@code completeMcpSignIn}, {@code sign-in.ts:185-211}). Pass the {@code redirectUrl}
     * given to {@link #start}.
     *
     * <p>Fails when the response carries another {@code state}, when another sign-in started with the
     * store since, or when the exchange would use another redirect URI than the authorization
     * request (the options changed).</p>
     *
     * @param options what to sign in to and how
     * @param redirectUrl the redirect URI given to {@link #start}
     * @param authorization the redirect result of {@link #start}
     * @param response the authorization response
     * @throws Exception when the exchange failed
     */
    public static void complete(Options options, String redirectUrl, McpSignInRedirect authorization,
                                OAuthCallback response) throws Exception {
        if (!Objects.equals(response.state(), authorization.state())) {
            throw new IllegalStateException("The authorization response belongs to a different sign-in");
        }
        // The stored PKCE verifier belongs to the latest sign-in; exchanging with it would fail with
        // `invalid_grant`, which discards the stored tokens (pi sign-in.ts:197-201).
        var stored = options.store().load();
        if (stored == null || !Objects.equals(stored.oauthState(), authorization.state())) {
            throw new IllegalStateException("Another sign-in to this MCP server started since; sign in again");
        }
        var provider = McpAuthProviders.createProvider(options.serverUrl(), options.store(),
                options.settings(), redirectUrl, options.clientName(), url -> {
                });
        OAuthFlow.authorizeMcp(provider,
                flowOptions(options, authorization.scope(), stepUp(options), response.code(),
                        response.iss(), authorization.redirectUrl()));
    }

    private static boolean stepUp(Options options) {
        return options.challenge() != null && "insufficient_scope".equals(options.challenge().error());
    }

    private static void abortIfRequested(@Nullable AbortSignal signal) {
        if (signal != null && signal.isAborted()) {
            throw new McpSignInCancelledError();
        }
    }

    /**
     * Refresh the stored tokens with the stored client (pi {@code refreshBeforeSignIn},
     * {@code sign-in.ts:88-105}). {@code false} means the browser step runs instead.
     */
    private static boolean refreshedBeforeSignIn(Options options, String redirectUrl,
                                                 @Nullable AbortSignal signal) throws Exception {
        var store = options.store();
        var refreshed = new CompletableFuture<Boolean>();
        Thread.startVirtualThread(() -> {
            try {
                refreshed.complete(refreshWithStoredClient(options, store, redirectUrl));
            } catch (Throwable error) {
                refreshed.completeExceptionally(error);
            }
        });
        try {
            return awaitAbortable(refreshed, signal);
        } catch (McpRefreshLockBusyError busy) {
            // Another process is refreshing and took too long. The user signs in instead.
            return false;
        }
    }

    /** The refresh itself, under the store's refresh lock, which it never re-enters. */
    private static boolean refreshWithStoredClient(Options options, McpOAuthServerStore store,
                                                   String redirectUrl) throws Exception {
        var settings = options.settings();
        return Boolean.TRUE.equals(store.withRefreshLock(() -> {
            var state = store.load();
            if (state == null || state.tokens() == null || state.tokens().refreshToken() == null) {
                return false;
            }
            // `cimd` is to replace a registered client, so refreshing with it is the wrong identity.
            if (settings.clientRegistration() == McpOAuthSettings.Registration.CIMD
                    && state.clientInformation() != null && settings.clientId() == null) {
                return false;
            }
            var provider = McpAuthProviders.createProvider(options.serverUrl(), store, settings,
                    redirectUrl, options.clientName(), url -> {
                    });
            var challenge = options.challenge();
            var flow = new OAuthFlowOptions(
                    URI.create(options.serverUrl()),
                    null,
                    null,
                    challenge == null ? null : challenge.scope(),
                    challenge == null ? null : challenge.resourceMetadataUrl(),
                    settings.authServerMetadataUrl(),
                    null,
                    false,
                    false,
                    true,
                    null);
            return OAuthFlow.authorizeMcp(provider, flow) == OAuthFlowResult.AUTHORIZED;
        }));
    }

    /**
     * Wait for the refresh. Aborting stops only the wait: a refresh the server answered may have
     * rotated the refresh token, so it still saves its answer (pi {@code untilAborted}).
     */
    private static boolean awaitAbortable(CompletableFuture<Boolean> refreshed,
                                          @Nullable AbortSignal signal) throws Exception {
        if (signal == null) {
            return join(refreshed);
        }
        var cancelled = new CompletableFuture<Boolean>();
        var unsubscribe = signal.onAbort(
                () -> cancelled.completeExceptionally(new McpSignInCancelledError()));
        var settled = new CompletableFuture<Boolean>();
        CompletableFuture.anyOf(refreshed, cancelled).whenComplete((value, error) -> {
            if (error != null) {
                settled.completeExceptionally(error);
            } else {
                settled.complete(Boolean.TRUE.equals(value));
            }
        });
        try {
            return join(settled);
        } finally {
            unsubscribe.run();
        }
    }

    private static <T> T join(CompletableFuture<T> future) throws Exception {
        try {
            return future.join();
        } catch (CompletionException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
    }

    /**
     * Prepare the browser step: a fresh {@code state}, and a client that cannot use
     * {@code redirectUrl} dropped with its tokens (pi {@code startBrowserStep},
     * {@code sign-in.ts:120-145}).
     */
    private static McpSignInRedirect browserStep(Options options, String redirectUrl, boolean stepUp)
            throws Exception {
        var store = options.store();
        var settings = options.settings();
        var challenge = options.challenge();
        var stored = store.load();
        if (stored != null) {
            store.save(prepare(stored, settings, McpOAuthSettings.offersClientMetadataDocument(
                    settings, options.clientName()), redirectUrl));
        }
        var authorizationUrl = new AtomicReference<URI>();
        var provider = McpAuthProviders.createProvider(options.serverUrl(), store, settings,
                redirectUrl, options.clientName(), authorizationUrl::set);
        // A server asking for more scope gets it on top of the configured scope and, since the
        // challenge may list only the missing scopes, on top of the scope granted so far.
        var scope = mergeScopes(settings.scope(),
                stepUp ? OAuthEndpoints.stepUpScope(
                        stored == null || stored.tokens() == null ? null : stored.tokens().scope(),
                        challenge.scope())
                        : challenge == null ? null : challenge.scope());
        // The refresh already happened; a second one would keep the old scope.
        OAuthFlow.authorizeMcp(provider, flowOptions(options, scope, true, null, null, null));
        var url = authorizationUrl.get();
        if (url == null) {
            throw new IllegalStateException("OAuth flow did not produce an authorization URL");
        }
        var state = provider.state();
        if (state == null) {
            throw new IllegalStateException("OAuth flow did not produce a state parameter");
        }
        // The flow picks the redirect URI, which may be specific to the MCP server.
        var chosen = queryParameter(url, "redirect_uri");
        return new McpSignInRedirect(url, chosen == null ? redirectUrl : chosen, state,
                queryParameter(url, "scope"));
    }

    private static OAuthFlowOptions flowOptions(Options options, @Nullable String scope, boolean stepUp,
                                                @Nullable String code, @Nullable String iss,
                                                @Nullable String authorizationRedirectUrl) {
        var challenge = options.challenge();
        return new OAuthFlowOptions(
                URI.create(options.serverUrl()),
                code,
                iss,
                scope,
                challenge == null ? null : challenge.resourceMetadataUrl(),
                options.settings().authServerMetadataUrl(),
                null,
                false,
                stepUp,
                false,
                authorizationRedirectUrl);
    }

    /** The state a sign-in starts from (pi {@code stateForSignIn}, {@code sign-in.ts:151-170}). */
    static McpOAuthState prepare(McpOAuthState stored, McpOAuthSettings settings,
                                 boolean offersDocument, String redirectUrl) {
        // Every sign-in gets a fresh `state` parameter.
        // A registered client cannot use another redirect URI, and its tokens belong to it. A
        // Client ID Metadata Document is not stored, so with one, a stored client was registered
        // before and is replaced.
        var keepClient = settings.clientId() != null
                || (offersDocument ? stored.clientInformation() == null
                        : registeredRedirectUrls(stored.clientInformation()).contains(redirectUrl));
        return new McpOAuthState(stored.serverUrl(),
                keepClient ? stored.clientInformation() : null,
                keepClient ? stored.tokens() : null,
                keepClient ? stored.tokensExpireAt() : null,
                stored.codeVerifier(),
                null,
                stored.discovery());
    }

    /** Scopes of every list, each once ({@code oauth.ts:105-109}). */
    static @Nullable String mergeScopes(@Nullable String... scopes) {
        var merged = new LinkedHashSet<String>();
        for (var scope : scopes) {
            if (scope == null) {
                continue;
            }
            for (var part : scope.split("\\s+")) {
                if (!part.isEmpty()) {
                    merged.add(part);
                }
            }
        }
        return merged.isEmpty() ? null : String.join(" ", merged);
    }

    /** {@code oauth.ts:223-225}. */
    private static List<String> registeredRedirectUrls(@Nullable OAuthClientInformation information) {
        return information == null || information.redirectUris() == null
                ? List.of() : information.redirectUris();
    }

    /** The decoded query parameters, first value of each name winning. */
    static Map<String, String> queryParameters(URI uri) {
        var parameters = new LinkedHashMap<String, String>();
        var query = uri.getRawQuery();
        if (query == null) {
            return parameters;
        }
        for (var pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            var separator = pair.indexOf('=');
            var name = separator < 0 ? pair : pair.substring(0, separator);
            var value = separator < 0 ? "" : pair.substring(separator + 1);
            parameters.putIfAbsent(decode(name), decode(value));
        }
        return parameters;
    }

    private static @Nullable String queryParameter(URI uri, String name) {
        return queryParameters(uri).get(name);
    }

    /** Percent-decoding with {@code +} for space, leaving a malformed escape alone. */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException expected) {
            return value;
        }
    }
}
