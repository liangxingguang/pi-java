package com.pijava.mcp.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

import org.junit.jupiter.api.Test;

import com.pijava.ai.AbortSignal;
import com.pijava.mcp.oauth.MemoryOAuthStateStore;
import com.pijava.mcp.oauth.OAuthError;
import com.pijava.mcp.oauth.OAuthIssuerMismatchError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing in against a real loopback authorization server, end to end
 * (pi {@code test/oauth.test.ts:96-280}; closes B217/#46).
 */
class McpSignInEndToEndTest {

    @Test
    void signsInThroughDiscoveryRegistrationPkceAndRefresh() throws Exception {
        try (var server = AuthorizationServerFixture.start(AuthorizationServerFixture.Mode.NORMAL)) {
            var store = new MemoryOAuthStateStore();
            var settings = McpOAuthSettings.none();
            var prompt = new BrowserPrompt();

            McpSignIn.signIn(new McpSignIn.Options(
                    server.mcpUrl(), store, settings, null, prompt, "pi-java"));

            var stored = store.load();
            assertThat(stored).isNotNull();
            assertThat(stored.tokens().accessToken()).isEqualTo("first-token");
            assertThat(stored.tokens().refreshToken()).isEqualTo("refresh-token");
            // The token response names no scope, so the grant has the requested scope.
            assertThat(stored.tokens().scope()).isEqualTo("org:read");
            assertThat(stored.clientInformation().clientId()).isEqualTo("test-client");
            assertThat(server.registrations()).isEqualTo(1);
            // Discovery's scope and resource reached the authorization request, which
            // used PKCE.
            assertThat(server.authorizationQuery())
                    .containsEntry("scope", "org:read")
                    .containsEntry("resource", server.mcpUrl())
                    .containsEntry("response_type", "code")
                    .containsEntry("code_challenge_method", "S256");
            assertThat(prompt.awaitCallback()).isTrue();

            // The stored token opens the MCP endpoint; a missing or wrong one does not.
            assertThat(postMcp(server.mcpUrl(), "Bearer first-token").statusCode()).isEqualTo(200);
            assertThat(postMcp(server.mcpUrl(), "Bearer wrong-token").statusCode()).isEqualTo(401);
            assertThat(postMcp(server.mcpUrl(), null).statusCode()).isEqualTo(401);

            // A second sign-in refreshes with the stored refresh token, without a browser.
            McpSignIn.signIn(new McpSignIn.Options(
                    server.mcpUrl(), store, settings, null, new BrowserPrompt(), "pi-java"));
            assertThat(server.refreshes()).isEqualTo(1);
            assertThat(server.exchanges()).isEqualTo(1);
            assertThat(store.load().tokens().accessToken()).isEqualTo("refreshed-token");
            // A refresh response without a refresh token keeps the one that was used.
            assertThat(store.load().tokens().refreshToken()).isEqualTo("refresh-token");
            assertThat(store.load().tokens().scope()).isEqualTo("org:read");
        }
    }

    @Test
    void aStateFromAnotherSignInFailsTheSignIn() throws Exception {
        try (var server = AuthorizationServerFixture.start(
                AuthorizationServerFixture.Mode.WRONG_STATE)) {
            assertThatThrownBy(() -> McpSignIn.signIn(new McpSignIn.Options(
                    server.mcpUrl(), new MemoryOAuthStateStore(), McpOAuthSettings.none(),
                    null, new BrowserPrompt(), "pi-java")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The redirect URL belongs to a different sign-in");
        }
    }

    @Test
    void anIssuerOtherThanTheDiscoveredOneFailsTheSignIn() throws Exception {
        try (var server = AuthorizationServerFixture.start(
                AuthorizationServerFixture.Mode.WRONG_ISSUER)) {
            assertThatThrownBy(() -> McpSignIn.signIn(new McpSignIn.Options(
                    server.mcpUrl(), new MemoryOAuthStateStore(), McpOAuthSettings.none(),
                    null, new BrowserPrompt(), "pi-java")))
                    .isInstanceOf(OAuthIssuerMismatchError.class)
                    .hasMessageContaining("https://wrong-issuer.test");
        }
    }

    @Test
    void aCodeVerifierThatDoesNotMatchTheChallengeFailsTheSignIn() throws Exception {
        try (var server = AuthorizationServerFixture.start(
                AuthorizationServerFixture.Mode.WRONG_VERIFIER)) {
            assertThatThrownBy(() -> McpSignIn.signIn(new McpSignIn.Options(
                    server.mcpUrl(), new MemoryOAuthStateStore(), McpOAuthSettings.none(),
                    null, new BrowserPrompt(), "pi-java")))
                    .isInstanceOf(OAuthError.class)
                    .hasMessage("invalid_grant");
            // The failed grant was exchanged twice: authorizeMcp retries once before giving up.
            assertThat(server.exchanges()).isEqualTo(2);
        }
    }

    private static HttpResponse<String> postMcp(String url, @Nullable String authorization)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * Opens the authorization URL the way a browser would: once the sign-in waits for
     * the callback, it follows the redirect to the loopback callback server and also
     * remembers the redirect URL, which is what a user pastes when the browser cannot
     * reach loopback.
     */
    private static final class BrowserPrompt implements McpSignInPrompt {

        private static final HttpClient CLIENT = HttpClient.newHttpClient();

        private final CountDownLatch waiting = new CountDownLatch(1);
        private final CompletableFuture<String> redirect = new CompletableFuture<>();
        private volatile boolean reachedCallback;

        @Override
        public void showAuthorizationUrl(URI url) {
            Thread.startVirtualThread(() -> {
                try {
                    // waitForAuthorizationResponse registers the callback before asking for
                    // a pasted URL, so following the redirect now always reaches it.
                    if (!waiting.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The sign-in never waited for the redirect URL");
                    }
                    var authorized = CLIENT.send(HttpRequest.newBuilder(url).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    var location = authorized.headers().firstValue("location").orElseThrow(() ->
                            new IllegalStateException("The authorization endpoint did not redirect"));
                    // The user's browser follows the redirect to loopback.
                    CLIENT.send(HttpRequest.newBuilder(URI.create(location)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    reachedCallback = true;
                    redirect.complete(location);
                } catch (Throwable failure) {
                    redirect.completeExceptionally(failure);
                }
            });
        }

        @Override
        public @Nullable String promptForRedirectUrl(AbortSignal signal) throws Exception {
            waiting.countDown();
            return redirect.get(10, TimeUnit.SECONDS);
        }

        /**
         * Whether the browser asked the loopback callback server. Waiting for the
         * redirect also makes sure the browser thread finished before it is read.
         */
        boolean awaitCallback() throws Exception {
            redirect.get(10, TimeUnit.SECONDS);
            return reachedCallback;
        }
    }
}
