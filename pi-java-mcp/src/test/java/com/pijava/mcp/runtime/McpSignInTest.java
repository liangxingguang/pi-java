package com.pijava.mcp.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.pijava.ai.AbortSignal;
import com.pijava.mcp.oauth.McpOAuthState;
import com.pijava.mcp.oauth.OAuthCallbackServer;
import com.pijava.mcp.oauth.OAuthCallbackServerOptions;
import com.pijava.mcp.oauth.OAuthClientInformation;
import com.pijava.mcp.oauth.OAuthTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code signInMcpServer} 的各个部件（{@code oauth.ts:385-532}）。
 */
class McpSignInTest {

    private static final URI REDIRECT = URI.create("http://127.0.0.1:1234/callback");

    // ------------------------------------------------------------------ mergeScopes

    @Test
    void mergesScopesEachOnceInOrder() {
        assertThat(McpSignIn.mergeScopes(null, null)).isNull();
        assertThat(McpSignIn.mergeScopes("", null)).isNull();
        assertThat(McpSignIn.mergeScopes("files:read files:write", "files:write other"))
                .isEqualTo("files:read files:write other");
    }

    @Test
    void stripsRepeatedWhitespaceBetweenScopes() {
        assertThat(McpSignIn.mergeScopes("  a   b ", null)).isEqualTo("a b");
    }

    // ------------------------------------------------------- responseFromRedirectUrl

    @Test
    void readsTheCodeAndIssFromAPastedRedirectUrl() {
        var response = McpSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?code=abc&state=s1&iss=https%3A%2F%2Fas.test",
                "s1", REDIRECT);
        assertThat(response.code()).isEqualTo("abc");
        assertThat(response.iss()).isEqualTo("https://as.test");
    }

    @Test
    void rejectsSomethingThatIsNotAUrl() {
        assertThatThrownBy(() -> McpSignIn.responseFromRedirectUrl("nonsense", "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected the full redirect URL from the browser address bar");
    }

    @Test
    void rejectsARedirectUrlForAnotherRedirectUri() {
        assertThatThrownBy(() -> McpSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:4321/other?code=abc&state=s1", "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The redirect URL does not match this sign-in's redirect URI");
    }

    @Test
    void reportsTheServersOwnErrorBeforeAnythingElse() {
        assertThatThrownBy(() -> McpSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?error=access_denied&state=wrong",
                "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("access_denied");
        assertThatThrownBy(() -> McpSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?error=access_denied&error_description=Nope",
                "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nope");
    }

    @Test
    void rejectsAStateFromAnotherSignIn() {
        assertThatThrownBy(() -> McpSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?code=abc", "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The redirect URL belongs to a different sign-in");
    }

    @Test
    void rejectsARedirectUrlWithoutACode() {
        assertThatThrownBy(() -> McpSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?state=s1&code=", "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The redirect URL does not contain an authorization code");
    }

    // -------------------------------------------------------------------- prepare

    private static McpOAuthState stored(OAuthClientInformation client) {
        return new McpOAuthState("http://example.test/mcp", client,
                new OAuthTokens("a", "Bearer", 3600, null, "r", null),
                1L, "verifier", "old-state", null);
    }

    private static OAuthClientInformation client(String... redirectUris) {
        return new OAuthClientInformation("cid", null, null, null, null, List.of(redirectUris), null);
    }

    private static McpOAuthSettings settings(String clientId, McpOAuthSettings.Registration registration) {
        return new McpOAuthSettings(clientId, null, null, null, null, null, registration, null);
    }

    @Test
    void aConfiguredClientIdIsAlwaysKept() {
        var next = McpSignIn.prepare(stored(client("http://elsewhere/callback")),
                settings("configured", null), false, "http://127.0.0.1:1234/callback");
        assertThat(next.clientInformation()).isNotNull();
        assertThat(next.tokens()).isNotNull();
        // Every sign-in gets a fresh state parameter.
        assertThat(next.oauthState()).isNull();
        assertThat(next.codeVerifier()).isEqualTo("verifier");
    }

    @Test
    void aRegisteredClientKeptOnlyWhenItsRedirectUrisCoverOurs() {
        var matching = McpSignIn.prepare(stored(client("http://127.0.0.1:1234/callback")),
                settings(null, null), false, "http://127.0.0.1:1234/callback");
        assertThat(matching.tokens()).isNotNull();

        var mismatched = McpSignIn.prepare(stored(client("http://127.0.0.1:9/callback")),
                settings(null, null), false, "http://127.0.0.1:1234/callback");
        assertThat(mismatched.clientInformation()).isNull();
        assertThat(mismatched.tokens()).isNull();
        assertThat(mismatched.tokensExpireAt()).isNull();
        assertThat(mismatched.codeVerifier()).isEqualTo("verifier");
    }

    @Test
    void aClientIdMetadataDocumentReplacesAnyStoredClient() {
        var replaced = McpSignIn.prepare(stored(client("http://127.0.0.1:1234/callback")),
                settings(null, McpOAuthSettings.Registration.CIMD), true, "http://127.0.0.1:1234/callback");
        assertThat(replaced.clientInformation()).isNull();

        var fresh = McpSignIn.prepare(stored(null),
                settings(null, McpOAuthSettings.Registration.CIMD), true, "http://127.0.0.1:1234/callback");
        assertThat(fresh.tokens()).isNotNull();
    }

    // ------------------------------------------ waitForAuthorizationResponse

    /** Answers the prompt with a fixed string. */
    private record PastingPrompt(String input, boolean pastes) implements McpSignInPrompt {

        @Override
        public void showAuthorizationUrl(URI url) {
            // The pasted-URL path does not use the browser.
        }

        @Override
        public String promptForRedirectUrl(AbortSignal signal) {
            return pastes ? input : null;
        }
    }

    @Test
    void aPastedRedirectUrlWinsWhenTheBrowserCannotReachLoopback() throws Exception {
        var callback = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        try {
            var response = McpSignIn.waitForAuthorizationResponse(callback, "s1",
                    URI.create(callback.redirectUrl()),
                    new PastingPrompt(callback.redirectUrl() + "?code=abc&state=s1", true));
            assertThat(response.code()).isEqualTo("abc");
        } finally {
            callback.close();
        }
    }

    @Test
    void cancellingThePromptCancelsTheSignIn() throws Exception {
        var callback = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        try {
            assertThatThrownBy(() -> McpSignIn.waitForAuthorizationResponse(callback, "s1",
                    URI.create(callback.redirectUrl()), new PastingPrompt("", false)))
                    .isInstanceOf(McpSignInCancelledError.class)
                    .hasMessage("Sign-in cancelled");
        } finally {
            callback.close();
        }
    }

    @Test
    void theBrowserCallbackWinsAndTheLosingPromptIsSwallowed() throws Exception {
        var callback = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        var release = new java.util.concurrent.CountDownLatch(1);
        var prompt = new McpSignInPrompt() {
            @Override
            public void showAuthorizationUrl(URI url) {
                // The browser reaches loopback, so the prompt is never used.
            }

            @Override
            public String promptForRedirectUrl(AbortSignal signal) throws Exception {
                // Never answered in time: the callback wins, and this later rejection is swallowed.
                release.await(5, TimeUnit.SECONDS);
                return null;
            }
        };
        try (var client = HttpClient.newHttpClient()) {
            var waiting = new java.util.concurrent.CompletableFuture<McpSignIn.AuthorizationResponse>();
            Thread.startVirtualThread(() -> {
                try {
                    waiting.complete(McpSignIn.waitForAuthorizationResponse(callback, "s1",
                            URI.create(callback.redirectUrl()), prompt));
                } catch (Throwable failure) {
                    waiting.completeExceptionally(failure);
                }
            });
            var answer = client.send(HttpRequest.newBuilder(
                            URI.create(callback.redirectUrl() + "?code=from-browser&state=s1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(answer.statusCode()).isEqualTo(200);
            assertThat(waiting.get(5, TimeUnit.SECONDS).code()).isEqualTo("from-browser");
        } finally {
            release.countDown();
            callback.close();
        }
    }
}
