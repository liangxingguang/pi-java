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
import com.pijava.mcp.oauth.OAuthCallback;
import com.pijava.mcp.oauth.OAuthCallbackServer;
import com.pijava.mcp.oauth.OAuthCallbackServerOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Waiting for a sign-in's authorization response, and reading a pasted redirect URL
 * (pi {@code loopback.ts:73-137}).
 */
class McpLoopbackSignInTest {

    private static final String REDIRECT = "http://127.0.0.1:1234/callback";

    private static McpSignInRedirect authorization(String redirectUrl) {
        return new McpSignInRedirect(URI.create("http://127.0.0.1/authorize"), redirectUrl, "s1", null);
    }

    // ------------------------------------------------------- responseFromRedirectUrl

    @Test
    void readsTheCodeAndIssFromAPastedRedirectUrl() {
        var response = McpLoopbackSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?code=abc&state=s1&iss=https%3A%2F%2Fas.test",
                "s1", REDIRECT);
        assertThat(response.code()).isEqualTo("abc");
        assertThat(response.state()).isEqualTo("s1");
        assertThat(response.iss()).isEqualTo("https://as.test");
    }

    @Test
    void rejectsSomethingThatIsNotAUrl() {
        assertThatThrownBy(() -> McpLoopbackSignIn.responseFromRedirectUrl("nonsense", "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected the full redirect URL from the browser address bar");
    }

    @Test
    void rejectsARedirectUrlForAnotherRedirectUri() {
        assertThatThrownBy(() -> McpLoopbackSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:4321/other?code=abc&state=s1", "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The redirect URL does not match this sign-in's redirect URI");
    }

    @Test
    void reportsTheServersOwnErrorBeforeAnythingElse() {
        assertThatThrownBy(() -> McpLoopbackSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?error=access_denied&state=wrong", "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("access_denied");
        assertThatThrownBy(() -> McpLoopbackSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?error=access_denied&error_description=Nope",
                "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nope");
    }

    @Test
    void rejectsAStateFromAnotherSignIn() {
        assertThatThrownBy(() -> McpLoopbackSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?code=abc", "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The redirect URL belongs to a different sign-in");
    }

    @Test
    void rejectsARedirectUrlWithoutACode() {
        assertThatThrownBy(() -> McpLoopbackSignIn.responseFromRedirectUrl(
                "http://127.0.0.1:1234/callback?state=s1&code=", "s1", REDIRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The redirect URL does not contain an authorization code");
    }

    // ------------------------------------------------------------------------ await

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
            var response = McpLoopbackSignIn.await(callback,
                    authorization(callback.redirectUrl()),
                    new PastingPrompt(callback.redirectUrl() + "?code=abc&state=s1", true), null);
            assertThat(response.code()).isEqualTo("abc");
        } finally {
            callback.close();
        }
    }

    @Test
    void cancellingThePromptCancelsTheSignIn() throws Exception {
        var callback = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        try {
            assertThatThrownBy(() -> McpLoopbackSignIn.await(callback,
                    authorization(callback.redirectUrl()), new PastingPrompt("", false), null))
                    .isInstanceOf(McpSignInCancelledError.class)
                    .hasMessage("Sign-in cancelled");
        } finally {
            callback.close();
        }
    }

    @Test
    void anAbortedSignalStopsTheWait() throws Exception {
        var callback = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        var signal = AbortSignal.create();
        try {
            var waiting = new CompletableFuture<OAuthCallback>();
            Thread.startVirtualThread(() -> {
                try {
                    waiting.complete(McpLoopbackSignIn.await(callback,
                            authorization(callback.redirectUrl()), null, signal));
                } catch (Throwable failure) {
                    waiting.completeExceptionally(failure);
                }
            });
            signal.abort();
            assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(McpSignInCancelledError.class);
        } finally {
            callback.close();
        }
    }

    @Test
    void theBrowserCallbackWinsAndTheLosingPromptIsSwallowed() throws Exception {
        var callback = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        var release = new CountDownLatch(1);
        var prompt = new McpSignInPrompt() {
            @Override
            public void showAuthorizationUrl(URI url) {
                // The browser reaches loopback, so the prompt is never used.
            }

            @Override
            public @Nullable String promptForRedirectUrl(AbortSignal signal) throws Exception {
                // Never answered in time: the callback wins, and this later rejection is swallowed.
                release.await(5, TimeUnit.SECONDS);
                return null;
            }
        };
        try (var client = HttpClient.newHttpClient()) {
            var waiting = new CompletableFuture<OAuthCallback>();
            Thread.startVirtualThread(() -> {
                try {
                    waiting.complete(McpLoopbackSignIn.await(callback,
                            authorization(callback.redirectUrl()), prompt, null));
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
