package com.pijava.mcp.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.utils.OAuthPage;
import com.pijava.mcp.oauth.McpOAuthState;
import com.pijava.mcp.oauth.OAuthCallback;
import com.pijava.mcp.oauth.OAuthCallbackPage;
import com.pijava.mcp.oauth.OAuthCallbackServer;
import com.pijava.mcp.oauth.OAuthCallbackServerOptions;
import com.pijava.mcp.oauth.OAuthChallenge;
import com.pijava.mcp.oauth.OAuthClientInformation;
import com.pijava.mcp.oauth.OAuthEndpoints;
import com.pijava.mcp.oauth.OAuthFlow;
import com.pijava.mcp.oauth.OAuthFlowOptions;
import com.pijava.mcp.oauth.OAuthFlowResult;
import com.pijava.mcp.oauth.OAuthStateStore;

/**
 * Sign in to an MCP server: use the stored refresh token when possible, otherwise run the browser
 * authorization code flow (pi {@code signInMcpServer}, {@code oauth.ts:452-532}).
 *
 * <p>Tokens are saved to the store. The browser flow talks to a loopback callback server, with a
 * pasted redirect URL as the fallback for when the browser cannot reach it.</p>
 */
public final class McpSignIn {

    private McpSignIn() {
    }

    /**
     * What a sign-in is built from (pi's options object, {@code oauth.ts:456-462}).
     *
     * @param serverUrl the MCP server URL
     * @param store the server's slice of {@code mcp-auth.json}
     * @param settings the OAuth configuration
     * @param challenge the challenge that asked for the sign-in, when there was one
     * @param prompt how to talk to the user
     * @param clientName the application name, the {@code client_name} fallback for registration
     */
    public record Options(
            String serverUrl,
            OAuthStateStore store,
            McpOAuthSettings settings,
            @Nullable OAuthChallenge challenge,
            McpSignInPrompt prompt,
            String clientName) {
    }

    /** One authorization response ({@code oauth.ts:383}). */
    record AuthorizationResponse(String code, @Nullable String iss) {
    }

    /**
     * Sign in, returning once the tokens are stored.
     *
     * @param options what to sign in to and how
     * @throws Exception when the flow failed; {@link McpSignInCancelledError} when the user
     *                   gave up
     */
    public static void signIn(Options options) throws Exception {
        var serverUrl = options.serverUrl();
        var store = options.store();
        var settings = options.settings();
        var challenge = options.challenge();
        var stored = store.load();
        var stepUp = challenge != null && "insufficient_scope".equals(challenge.error());
        var callbackOptions = McpCallbackSettings.from(settings);
        // Reuse the port of the registered redirect URI so the registered client stays valid.
        var registered = McpAuthProviders.firstRegisteredRedirectUrl(
                stored == null ? null : stored.clientInformation());
        var preferredPort = callbackOptions.port() != null ? callbackOptions.port()
                : registered == null ? null : WebUrls.port(URI.create(registered));
        var cimd = settings.clientRegistration() == McpOAuthSettings.Registration.CIMD;
        var callback = listenForCallback(callbackOptions,
                // The redirect URI of a server-specific Client ID Metadata Document.
                cimd ? List.of(McpCallbackSettings.CALLBACK_PATH + "/"
                        + McpClientMetadataDocuments.callbackId(serverUrl)) : List.of(),
                preferredPort,
                callbackOptions.port() != null);
        var redirectUrl = callbackOptions.fixedRedirectUrl() != null
                ? callbackOptions.fixedRedirectUrl() : callback.redirectUrl();
        try {
            if (stored != null) {
                store.save(prepare(stored, settings, cimd, redirectUrl));
            }
            var authorizationUrl = new AtomicReference<URI>();
            var provider = McpAuthProviders.createProvider(serverUrl, store, settings, redirectUrl,
                    options.clientName(), authorizationUrl::set);
            // A server asking for more scope gets it on top of the configured scope and, since the
            // challenge may list only the missing scopes, on top of the scope granted so far.
            var scope = mergeScopes(settings.scope(),
                    stepUp ? OAuthEndpoints.stepUpScope(
                            stored == null || stored.tokens() == null ? null : stored.tokens().scope(),
                            challenge.scope())
                            : challenge == null ? null : challenge.scope());
            var flow = flowOptions(options, scope, stepUp, null, null);
            if (OAuthFlow.authorizeMcp(provider, flow) == OAuthFlowResult.AUTHORIZED) {
                return;
            }
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
            var authorizationRedirectUrl = URI.create(chosen == null ? redirectUrl : chosen);
            options.prompt().showAuthorizationUrl(url);
            var response = waitForAuthorizationResponse(callback, state, authorizationRedirectUrl,
                    options.prompt());
            OAuthFlow.authorizeMcp(provider,
                    flowOptions(options, scope, stepUp, response.code(), response.iss()));
        } finally {
            callback.close();
        }
    }

    /** The state a sign-in starts from ({@code oauth.ts:481-496}). */
    static McpOAuthState prepare(McpOAuthState stored, McpOAuthSettings settings,
                                         boolean cimd, String redirectUrl) {
        // Every sign-in gets a fresh `state` parameter.
        // A registered client cannot use another redirect URI, and its tokens belong to it. A
        // Client ID Metadata Document is not stored, so with one, a stored client was registered
        // before and is replaced.
        var keepClient = settings.clientId() != null
                || (cimd ? stored.clientInformation() == null
                        : registeredRedirectUrls(stored.clientInformation()).contains(redirectUrl));
        return new McpOAuthState(stored.serverUrl(),
                keepClient ? stored.clientInformation() : null,
                keepClient ? stored.tokens() : null,
                keepClient ? stored.tokensExpireAt() : null,
                stored.codeVerifier(),
                null,
                stored.discovery());
    }

    private static OAuthFlowOptions flowOptions(Options options, @Nullable String scope,
                                                boolean stepUp, @Nullable String code,
                                                @Nullable String iss) {
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
                stepUp);
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

    /** {@code responseFromRedirectUrl} ({@code oauth.ts:385-402}). */
    static AuthorizationResponse responseFromRedirectUrl(String input, String state, URI redirectUrl) {
        URI url;
        try {
            url = URI.create(input.strip());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Expected the full redirect URL from the browser address bar", error);
        }
        // URI.create accepts a relative reference; `new URL` does not, so the scheme is the
        // check that makes the two agree.
        if (url.getScheme() == null) {
            throw new IllegalStateException(
                    "Expected the full redirect URL from the browser address bar");
        }
        // A server-specific redirect URI tells authorization servers apart, so it must match exactly.
        if (!Objects.equals(origin(url), origin(redirectUrl))
                || !WebUrls.pathname(url).equals(WebUrls.pathname(redirectUrl))) {
            throw new IllegalStateException("The redirect URL does not match this sign-in's redirect URI");
        }
        var parameters = queryParameters(url);
        var error = parameters.get("error");
        if (error != null) {
            throw new IllegalStateException(parameters.getOrDefault("error_description", error));
        }
        if (!Objects.equals(parameters.get("state"), state)) {
            throw new IllegalStateException("The redirect URL belongs to a different sign-in");
        }
        var code = parameters.get("code");
        if (code == null || code.isEmpty()) {
            throw new IllegalStateException("The redirect URL does not contain an authorization code");
        }
        return new AuthorizationResponse(code, parameters.get("iss"));
    }

    /** Wait for the browser callback or a pasted redirect URL, whichever comes first. */
    static AuthorizationResponse waitForAuthorizationResponse(
            OAuthCallbackServer callback, String state, URI redirectUrl, McpSignInPrompt prompt) {
        var controller = AbortSignal.create();
        CompletableFuture<OAuthCallback> fromBrowser =
                callback.waitForCallback(state, WebUrls.pathname(redirectUrl));
        var fromUser = new CompletableFuture<AuthorizationResponse>();
        Thread.startVirtualThread(() -> {
            try {
                var input = prompt.promptForRedirectUrl(controller);
                if (input == null || input.isBlank()) {
                    throw new McpSignInCancelledError();
                }
                fromUser.complete(responseFromRedirectUrl(input, state, redirectUrl));
            } catch (Throwable failure) {
                fromUser.completeExceptionally(failure);
            }
        });
        try {
            var winner = CompletableFuture.anyOf(fromBrowser.thenApply(callback1 -> (Object) callback1),
                    fromUser.thenApply(response -> (Object) response)).join();
            if (winner instanceof OAuthCallback fromCallback) {
                return new AuthorizationResponse(fromCallback.code(), fromCallback.iss());
            }
            return (AuthorizationResponse) winner;
        } catch (CompletionException failure) {
            throw failure.getCause() instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException(failure.getCause());
        } finally {
            // The losing side rejects once the prompt is aborted or the callback server closes.
            controller.abort();
            fromBrowser.exceptionally(ignored -> null);
            fromUser.exceptionally(ignored -> null);
        }
    }

    /** Listen on {@code port}, or on a free port when it is taken and not required. */
    private static OAuthCallbackServer listenForCallback(
            McpCallbackSettings settings, List<String> extraPaths, @Nullable Integer port, boolean required)
            throws IOException {
        Function<OAuthCallbackPage, String> pageRenderer = McpSignIn::renderCallbackPage;
        var options = new OAuthCallbackServerOptions(settings.host(), settings.redirectHost(),
                port == null ? 0 : port, settings.path(), extraPaths, null, pageRenderer);
        try {
            return OAuthCallbackServer.listen(options);
        } catch (IOException error) {
            if (required || port == null) {
                throw error;
            }
            return OAuthCallbackServer.listen(new OAuthCallbackServerOptions(
                    settings.host(), settings.redirectHost(), 0, settings.path(), extraPaths, null,
                    pageRenderer));
        }
    }

    private static String renderCallbackPage(OAuthCallbackPage page) {
        if (page instanceof OAuthCallbackPage.Ok) {
            return OAuthPage.success("Signed in to the MCP server. You may now close this page.");
        }
        var failed = (OAuthCallbackPage.Failed) page;
        return OAuthPage.error(failed.message(), failed.details());
    }

    /** {@code url.origin}: scheme, host, and a non-default port. */
    private static String origin(URI uri) {
        var port = WebUrls.port(uri);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + WebUrls.hostname(uri)
                + (port == null ? "" : ":" + port);
    }

    /** The decoded query parameters, first value of each name winning. */
    private static Map<String, String> queryParameters(URI uri) {
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
