package com.pijava.mcp.runtime;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.pijava.mcp.AuthProvider;
import com.pijava.mcp.oauth.McpOAuthAuthorizationRequiredError;
import com.pijava.mcp.oauth.McpOAuthState;
import com.pijava.mcp.oauth.OAuthChallenge;
import com.pijava.mcp.oauth.OAuthTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code createMcpAuthProvider}（{@code oauth.ts:302-363}）。
 */
class McpAuthProvidersTest {

    private static final String URL = "http://example.test/mcp";
    private static final long HOUR_MS = 3_600_000;

    /** A store whose refresh lock blocks the first caller, so single flight is observable. */
    private static final class BlockingStore implements McpOAuthServerStore {

        private final AtomicInteger lockEntries = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public @Nullable McpOAuthState load() {
            return null;
        }

        @Override
        public void save(McpOAuthState state) {
        }

        @Override
        public <T> T withRefreshLock(Callable<T> action) throws Exception {
            if (lockEntries.incrementAndGet() == 1) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            // The refresh body itself is not run: this fixture is about the single flight, not
            // about what a refresh does once it has the lock.
            return null;
        }
    }

    /** A store that runs the refresh body against a fixed state. */
    private static final class RunActionStore implements McpOAuthServerStore {

        private final AtomicReference<McpOAuthState> state;
        private final AtomicInteger lockEntries = new AtomicInteger();

        private RunActionStore(@Nullable McpOAuthState state) {
            this.state = new AtomicReference<>(state);
        }

        @Override
        public @Nullable McpOAuthState load() {
            return state.get();
        }

        @Override
        public void save(McpOAuthState value) {
            state.set(value);
        }

        @Override
        public <T> T withRefreshLock(Callable<T> action) throws Exception {
            lockEntries.incrementAndGet();
            return action.call();
        }
    }

    private static McpOAuthState withTokens(String accessToken, @Nullable String refreshToken, long expireAt) {
        return new McpOAuthState(URL, null,
                new OAuthTokens(accessToken, "Bearer", 3600, null, refreshToken, null),
                expireAt, null, null, null);
    }

    private static AuthProvider.Context context(@Nullable String token) {
        return new AuthProvider.Context(401, "", URL, token, null);
    }

    private static CompletableFuture<Void> runAsync(ThrowingRunnable action) {
        var future = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /** A body that may throw checked exceptions. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static McpAuthProvider provider(McpOAuthServerStore store, List<OAuthChallenge> challenges) {
        return McpAuthProviders.create(new McpAuthProviders.Options(
                URI.create(URL), store,
                () -> {
                    throw new IllegalStateException("settings were read");
                },
                challenges::add, "pi-java"));
    }

    // ------------------------------------------------------------------ single flight

    @Test
    void concurrentChallengesShareOneRefresh() throws Exception {
        var store = new BlockingStore();
        var challenges = new ArrayList<OAuthChallenge>();
        var seen = new CountDownLatch(2);
        var provider = McpAuthProviders.create(new McpAuthProviders.Options(
                URI.create(URL), store,
                () -> {
                    throw new AssertionError("settings must not be read");
                },
                challenge -> {
                    challenges.add(challenge);
                    seen.countDown();
                },
                "pi-java"));

        var first = runAsync(() -> provider.onUnauthorized(context("stale")));
        assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
        var second = runAsync(() -> provider.onUnauthorized(context("stale")));
        // Both callers are past the challenge hook, so both are at the refresh, and the first
        // one still holds the lock: the entries counted now are the whole story.
        assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
        store.release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertThat(store.lockEntries.get()).isEqualTo(1);
        assertThat(challenges).hasSize(2);
    }

    // --------------------------------------------------------------- refresh decisions

    @Test
    void aTokenAnotherProcessAlreadyReplacedIsNotRefreshed() throws Exception {
        var store = new RunActionStore(withTokens("replaced", "r", 1L));
        var provider = provider(store, new ArrayList<>());

        provider.onUnauthorized(context("stale"));
        assertThat(store.lockEntries.get()).isEqualTo(1);
    }

    @Test
    void aRefreshWithTheSameTokenReadsTheSettings() {
        var store = new RunActionStore(withTokens("stale", "r", 1L));
        var provider = provider(store, new ArrayList<>());

        assertThatThrownBy(() -> provider.onUnauthorized(context("stale")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("settings were read");
    }

    @Test
    void withoutARefreshTokenTheUserHasToSignIn() {
        var store = new RunActionStore(withTokens("stale", null, 1L));
        var provider = provider(store, new ArrayList<>());

        assertThatThrownBy(() -> provider.onUnauthorized(context("stale")))
                .isInstanceOf(McpOAuthAuthorizationRequiredError.class);
    }

    @Test
    void insufficientScopeAsksForASignInWithoutRefreshing() {
        var store = new RunActionStore(withTokens("stale", "r", 1L));
        var challenges = new ArrayList<OAuthChallenge>();
        var provider = provider(store, challenges);

        var challengeContext = new AuthProvider.Context(403,
                "Bearer error=\"insufficient_scope\", scope=\"files:write\"", URL, "stale", null);
        assertThatThrownBy(() -> provider.onUnauthorized(challengeContext))
                .isInstanceOf(McpOAuthAuthorizationRequiredError.class);

        // The challenge is recorded even though the refresh never runs (oauth.ts:354).
        assertThat(challenges).hasSize(1);
        assertThat(challenges.get(0).scope()).isEqualTo("files:write");
        assertThat(store.lockEntries.get()).isZero();
    }

    // ------------------------------------------------------------------------ token

    @Test
    void anUnexpiredTokenIsReturnedWithoutRefreshing() throws Exception {
        var store = new RunActionStore(withTokens("good", "r", System.currentTimeMillis() + HOUR_MS));
        var provider = provider(store, new ArrayList<>());

        assertThat(provider.token()).isEqualTo("good");
        assertThat(store.lockEntries.get()).isZero();
    }

    @Test
    void aTokenInsideTheSkewIsTreatedAsExpired() throws Exception {
        // 30 s of skew: a token expiring in 10 s is refreshed before it is sent (oauth.ts:46).
        var store = new RunActionStore(withTokens("soon", null, System.currentTimeMillis() + 10_000));
        var provider = provider(store, new ArrayList<>());

        // No refresh token, so the expired token is sent anyway and a 401 decides.
        assertThat(provider.token()).isEqualTo("soon");
        assertThat(store.lockEntries.get()).isZero();
    }

    @Test
    void noStoredStateMeansNoToken() throws Exception {
        assertThat(provider(new RunActionStore(null), new ArrayList<>()).token()).isNull();
    }

    @Test
    void settlingWithoutARefreshReturns() throws Exception {
        provider(new RunActionStore(withTokens("x", "r", 1L)), new ArrayList<>()).settled();
    }
}
