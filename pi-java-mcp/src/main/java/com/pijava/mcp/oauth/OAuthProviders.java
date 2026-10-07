package com.pijava.mcp.oauth;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.AuthProvider;

/**
 * Adapts an {@link OAuthClientProvider} to the transport's {@link AuthProvider}
 * (pi flow.ts:413-449).
 *
 * <p>Concurrent challenges share one flow. A request whose token was already
 * replaced is just retried: with rotating refresh tokens, a second refresh
 * using the old refresh token would fail and discard the new grant.</p>
 */
public final class OAuthProviders {

    private OAuthProviders() {
    }

    /** Build the transport-facing provider. */
    public static AuthProvider adaptOAuthProvider(OAuthClientProvider provider) {
        return new AdaptedProvider(provider);
    }

    private static final class AdaptedProvider implements AuthProvider {

        private final OAuthClientProvider provider;
        private final Object lock = new Object();
        private @Nullable CompletableFuture<Void> inFlight;

        private AdaptedProvider(OAuthClientProvider provider) {
            this.provider = provider;
        }

        @Override
        public @Nullable String token() {
            var tokens = provider.tokens();
            return tokens == null ? null : tokens.accessToken();
        }

        @Override
        public void onUnauthorized(Context context) throws Exception {
            var challenge = WwwAuthenticate.parse(context.wwwAuthenticate());
            var insufficientScope = "insufficient_scope".equals(challenge.error());
            CompletableFuture<Void> flow;
            var leader = false;
            synchronized (lock) {
                if (!insufficientScope && inFlight == null && context.token() != null) {
                    var current = provider.tokens();
                    if (current != null && !current.accessToken().equals(context.token())) {
                        return;
                    }
                }
                if (inFlight == null) {
                    inFlight = new CompletableFuture<>();
                    leader = true;
                }
                flow = inFlight;
            }
            if (leader) {
                try {
                    authorize(context, challenge, insufficientScope);
                    flow.complete(null);
                } catch (Throwable error) {
                    flow.completeExceptionally(error);
                } finally {
                    synchronized (lock) {
                        inFlight = null;
                    }
                }
            }
            try {
                flow.join();
            } catch (CompletionException error) {
                if (error.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw error;
            }
        }

        private void authorize(Context context, OAuthChallenge challenge, boolean insufficientScope)
                throws Exception {
            var granted = insufficientScope ? provider.tokens() : null;
            var scope = insufficientScope
                    ? OAuthEndpoints.stepUpScope(granted == null ? null : granted.scope(),
                            challenge.scope())
                    : challenge.scope();
            var options = new OAuthFlowOptions(
                    URI.create(context.serverUrl()),
                    null, null, scope,
                    challenge.resourceMetadataUrl(), null,
                    context.fetch(), false, insufficientScope);
            if (OAuthFlow.authorizeMcp(provider, options) == OAuthFlowResult.REDIRECT) {
                throw new McpOAuthAuthorizationRequiredError();
            }
        }
    }
}
