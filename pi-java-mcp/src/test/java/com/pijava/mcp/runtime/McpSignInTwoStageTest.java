package com.pijava.mcp.runtime;

import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.pijava.ai.AbortSignal;
import com.pijava.mcp.oauth.McpOAuthState;
import com.pijava.mcp.oauth.OAuthCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two halves of a sign-in: {@code startMcpSignIn} returns plain data, and
 * {@code completeMcpSignIn} refuses a response that does not belong to it
 * (pi {@code sign-in.ts:76-211}).
 */
class McpSignInTwoStageTest {

    private static final String SERVER_URL = "http://127.0.0.1:1/mcp";
    private static final String REDIRECT_URL = "http://127.0.0.1:1234/callback";

    /** A prompt the sign-in must not use: these tests drive both halves themselves. */
    private static final McpSignInPrompt NO_PROMPT = new McpSignInPrompt() {
        @Override
        public void showAuthorizationUrl(URI url) {
        }

        @Override
        public @Nullable String promptForRedirectUrl(AbortSignal signal) {
            return null;
        }
    };

    private static McpSignIn.Options options(String serverUrl, McpOAuthServerStore store,
                                             McpSignInPrompt prompt) {
        return new McpSignIn.Options(serverUrl, store, McpOAuthSettings.none(), null, prompt,
                "pi-java");
    }

    private static McpSignInRedirect redirect(String state) {
        return new McpSignInRedirect(URI.create("https://as.test/authorize"), REDIRECT_URL, state,
                "org:read");
    }

    @Test
    void startReturnsTheBrowserStepAsPlainData() throws Exception {
        try (var server = AuthorizationServerFixture.start(AuthorizationServerFixture.Mode.NORMAL)) {
            var store = new MemoryServerStore();
            var prompt = new McpSignInPrompt() {
                @Override
                public void showAuthorizationUrl(URI url) {
                    throw new AssertionError("start must not open a browser");
                }

                @Override
                public @Nullable String promptForRedirectUrl(AbortSignal signal) {
                    throw new AssertionError("start must not ask for a redirect URL");
                }
            };

            var started = McpSignIn.start(options(server.mcpUrl(), store, prompt),
                    REDIRECT_URL, null);

            assertThat(started).isInstanceOf(McpSignInRedirect.class);
            var authorization = (McpSignInRedirect) started;
            assertThat(authorization.authorizationUrl().toString())
                    .startsWith(server.origin() + "/authorize");
            assertThat(authorization.state()).isEqualTo(store.load().oauthState());
            assertThat(authorization.redirectUrl()).isEqualTo(REDIRECT_URL);
            // Read from the request, so a token response without a scope records what was asked.
            assertThat(authorization.scope()).isEqualTo("org:read");
        }
    }

    @Test
    void theTwoHalvesCompleteASignIn() throws Exception {
        try (var server = AuthorizationServerFixture.start(AuthorizationServerFixture.Mode.NORMAL)) {
            var store = new MemoryServerStore();
            var prompt = new BrowserPrompt();
            var callback = McpLoopbackSignIn.listen(server.mcpUrl(), store,
                    McpOAuthSettings.none(), "pi-java");
            try {
                var started = McpSignIn.start(options(server.mcpUrl(), store, prompt),
                        callback.redirectUrl(), null);
                var authorization = (McpSignInRedirect) started;
                assertThat(authorization.redirectUrl()).isEqualTo(callback.redirectUrl());

                prompt.showAuthorizationUrl(authorization.authorizationUrl());
                var response = McpLoopbackSignIn.await(callback.server(), authorization, prompt, null);
                McpSignIn.complete(options(server.mcpUrl(), store, prompt), callback.redirectUrl(),
                        authorization, response);

                assertThat(store.load().tokens().accessToken()).isEqualTo("first-token");
                assertThat(server.exchanges()).isEqualTo(1);
            } finally {
                callback.server().close();
            }
        }
    }

    @Test
    void completeRejectsAResponseFromAnotherSignIn() {
        var store = new MemoryServerStore();
        var response = new OAuthCallback("code", "another-state", null);

        assertThatThrownBy(() -> McpSignIn.complete(options(SERVER_URL, store, NO_PROMPT),
                REDIRECT_URL, redirect("s1"), response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The authorization response belongs to a different sign-in");
    }

    @Test
    void completeRejectsASignInAnotherOneReplaced() {
        var store = new MemoryServerStore();
        store.save(new McpOAuthState(SERVER_URL, null, null, null, "verifier", "newer-state", null));
        var response = new OAuthCallback("code", "s1", null);

        assertThatThrownBy(() -> McpSignIn.complete(options(SERVER_URL, store, NO_PROMPT),
                REDIRECT_URL, redirect("s1"), response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Another sign-in to this MCP server started since; sign in again");
    }

    @Test
    void abortingWhileWaitingForTheRefreshLockCancelsTheStart() throws Exception {
        var store = new BlockingLockStore();
        var signal = AbortSignal.create();
        var started = new CompletableFuture<McpSignInStart>();
        Thread.startVirtualThread(() -> {
            try {
                started.complete(McpSignIn.start(options(SERVER_URL, store, NO_PROMPT),
                        REDIRECT_URL, signal));
            } catch (Throwable error) {
                started.completeExceptionally(error);
            }
        });
        assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();

        signal.abort();
        assertThatThrownBy(() -> started.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(McpSignInCancelledError.class);

        // The wait stopped, but the refresh itself still runs and may save rotated tokens.
        store.release.countDown();
        assertThat(store.actions.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /** A store whose refresh lock blocks until the test releases it. */
    private static final class BlockingLockStore implements McpOAuthServerStore {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch actions = new CountDownLatch(1);

        @Override
        public @Nullable McpOAuthState load() {
            return null;
        }

        @Override
        public void save(McpOAuthState state) {
        }

        @Override
        public <T> T withRefreshLock(Callable<T> action) throws Exception {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            try {
                return action.call();
            } finally {
                actions.countDown();
            }
        }
    }
}
