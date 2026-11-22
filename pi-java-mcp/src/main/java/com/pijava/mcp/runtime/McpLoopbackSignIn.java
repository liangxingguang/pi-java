package com.pijava.mcp.runtime;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.utils.OAuthPage;
import com.pijava.mcp.oauth.OAuthCallback;
import com.pijava.mcp.oauth.OAuthCallbackPage;
import com.pijava.mcp.oauth.OAuthCallbackServer;
import com.pijava.mcp.oauth.OAuthCallbackServerOptions;
import com.pijava.mcp.oauth.OAuthStateStore;

/**
 * How the authorization response of a sign-in reaches a local application: a loopback callback
 * server the browser redirects to, or a redirect URL the user pastes from the browser address bar
 * (pi {@code loopback.ts:1-137}).
 */
public final class McpLoopbackSignIn {

    private McpLoopbackSignIn() {
    }

    /** A loopback callback server and the redirect URI it serves (pi {@code McpLoopbackCallback}). */
    public record Callback(OAuthCallbackServer server, String redirectUrl) {
    }

    /**
     * Listen on loopback where the settings say: {@code callbackUrl} and {@code callbackPort}, else
     * the port of the stored client's redirect URI (so that client stays valid), else a free port
     * (pi {@code listenForLoopbackSignIn}, {@code loopback.ts:36-66}).
     *
     * <p>Pass the returned redirect URI to both halves of the sign-in, and close the callback server
     * when the sign-in ends.</p>
     *
     * @param serverUrl the MCP server URL
     * @param store the server's state, for the port a stored client was registered on
     * @param settings the OAuth configuration
     * @param clientName the application name, deciding whether a Client ID Metadata Document applies
     * @return the callback server and its redirect URI
     * @throws IOException when no port can be bound
     */
    public static Callback listen(String serverUrl, OAuthStateStore store, McpOAuthSettings settings,
                                  String clientName) throws IOException {
        var loopback = McpCallbackSettings.from(settings);
        var state = store.load();
        var registered = McpAuthProviders.firstRegisteredRedirectUrl(
                state == null ? null : state.clientInformation());
        var port = loopback.port() != null ? loopback.port()
                : registered == null ? null : WebUrls.port(URI.create(registered));
        var offersDocument = McpOAuthSettings.offersClientMetadataDocument(settings, clientName);
        var callback = listenForCallback(loopback,
                // The redirect URI of a server-specific Client ID Metadata Document.
                offersDocument ? List.of(McpCallbackSettings.CALLBACK_PATH + "/"
                        + McpClientMetadataDocuments.callbackId(serverUrl)) : List.of(),
                port, loopback.port() != null);
        // A configured URI is sent exactly as written; otherwise the bound port goes in.
        return new Callback(callback, loopback.fixedRedirectUrl() != null
                ? loopback.fixedRedirectUrl() : callback.redirectUrl());
    }

    /**
     * Wait for the authorization response on the callback server, or for a redirect URL the user
     * pastes, whichever comes first (pi {@code waitForLoopbackSignIn}, {@code loopback.ts:73-108}).
     *
     * @param callback the callback server from {@link #listen}
     * @param authorization the redirect result of {@link McpSignIn#start}
     * @param prompt where to ask for a pasted redirect URL, or {@code null} not to ask
     * @param signal aborts the wait with {@link McpSignInCancelledError}
     * @return the authorization response
     * @throws Exception when the response failed; {@link McpSignInCancelledError} when the user gave up
     */
    public static OAuthCallback await(OAuthCallbackServer callback, McpSignInRedirect authorization,
                                     @Nullable McpSignInPrompt prompt, @Nullable AbortSignal signal)
            throws Exception {
        // The prompt's signal: aborted once the callback arrives, or when the sign-in is cancelled.
        var controller = AbortSignal.create();
        Runnable unsubscribe = signal == null ? () -> { } : signal.onAbort(controller::abort);
        var fromBrowser = callback.waitForCallback(authorization.state(),
                WebUrls.pathname(URI.create(authorization.redirectUrl())));
        var fromUser = new CompletableFuture<OAuthCallback>();
        var cancelled = new CompletableFuture<OAuthCallback>();
        controller.onAbort(() -> cancelled.completeExceptionally(new McpSignInCancelledError()));
        if (prompt != null) {
            Thread.startVirtualThread(() -> {
                try {
                    var input = prompt.promptForRedirectUrl(controller);
                    if (input == null || input.isBlank()) {
                        throw new McpSignInCancelledError();
                    }
                    fromUser.complete(responseFromRedirectUrl(input, authorization.state(),
                            authorization.redirectUrl()));
                } catch (Throwable failure) {
                    fromUser.completeExceptionally(failure);
                }
            });
        }
        try {
            var winner = CompletableFuture.anyOf(fromBrowser, fromUser, cancelled).join();
            return (OAuthCallback) winner;
        } catch (CompletionException failure) {
            throw failure.getCause() instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException(failure.getCause());
        } finally {
            // The losing side rejects once the prompt is aborted or the callback server closes.
            controller.abort();
            unsubscribe.run();
            fromBrowser.exceptionally(ignored -> null);
            fromUser.exceptionally(ignored -> null);
        }
    }

    /**
     * The authorization response in a redirect URL the user pasted (pi {@code responseFromRedirectUrl},
     * {@code loopback.ts:114-137}). Fails when the URL is not on the sign-in's redirect URI, carries
     * another {@code state}, or reports an error.
     *
     * @param input the pasted URL
     * @param state the {@code state} of the sign-in
     * @param redirectUrl the redirect URI of the sign-in
     * @return the authorization response
     */
    public static OAuthCallback responseFromRedirectUrl(String input, String state, String redirectUrl) {
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
        var expected = URI.create(redirectUrl);
        // A server-specific redirect URI tells authorization servers apart, so it must match exactly.
        if (!Objects.equals(origin(url), origin(expected))
                || !WebUrls.pathname(url).equals(WebUrls.pathname(expected))) {
            throw new IllegalStateException("The redirect URL does not match this sign-in's redirect URI");
        }
        var parameters = McpSignIn.queryParameters(url);
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
        return new OAuthCallback(code, state, parameters.get("iss"));
    }

    /** Listen on {@code port}, or on a free port when it is taken and not required. */
    private static OAuthCallbackServer listenForCallback(
            McpCallbackSettings settings, List<String> extraPaths, @Nullable Integer port, boolean required)
            throws IOException {
        Function<OAuthCallbackPage, String> pageRenderer = McpLoopbackSignIn::renderCallbackPage;
        var options = new OAuthCallbackServerOptions(settings.host(), settings.redirectHost(),
                port == null ? 0 : port, settings.path(), extraPaths, null, pageRenderer);
        try {
            return OAuthCallbackServer.listen(options);
        } catch (IOException error) {
            // A configured port is required; the stored client's port is only preferred.
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
}
