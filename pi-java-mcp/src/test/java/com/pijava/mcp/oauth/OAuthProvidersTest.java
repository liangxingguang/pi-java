package com.pijava.mcp.oauth;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.AuthProvider;
import com.pijava.mcp.McpFetch;

import static org.assertj.core.api.Assertions.assertThat;

/** Concurrent challenge handling (pi oauth.test.ts clause 2). */
class OAuthProvidersTest {

    private static final String AS = OAuthFlowTest.AS;
    private static final String TOKEN = OAuthFlowTest.TOKEN;

    @Test
    void sharesOneRefreshBetweenConcurrentChallenges() throws Exception {
        var delegate = new ScriptedMcpFetch()
                .json(TOKEN, 200, "{\"access_token\":\"a2\",\"refresh_token\":\"r2\","
                        + "\"token_type\":\"Bearer\"}");
        var firstInToken = new CountDownLatch(1);
        var secondInToken = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entries = new AtomicInteger();
        var fetch = slowToken(delegate, entries, firstInToken, secondInToken, release);
        var provider = provider();
        var auth = OAuthProviders.adaptOAuthProvider(provider);
        var failures = new CopyOnWriteArrayList<Throwable>();

        // Dedicated virtual threads: a shared pool may serialize the tasks, which lets
        // the "already replaced" guard mask a missing shared-refresh path.
        var first = Thread.ofVirtual().start(() -> challenge(auth, fetch, "a1", failures));
        assertThat(firstInToken.await(5, TimeUnit.SECONDS)).isTrue();
        var second = Thread.ofVirtual().start(() -> challenge(auth, fetch, "a1", failures));

        // The second challenge must wait for the in-flight refresh instead of starting
        // its own. Counting requests afterwards cannot tell the two apart: a late second
        // attempt is short-circuited by the "token already replaced" guard.
        assertThat(secondInToken.await(400, TimeUnit.MILLISECONDS))
                .as("second challenge started its own token request")
                .isFalse();

        release.countDown();
        first.join();
        second.join();

        assertThat(failures).isEmpty();
        assertThat(delegate.sentTo(TOKEN)).hasSize(1);
        assertThat(auth.token()).isEqualTo("a2");
        assertThat(provider.tokens().refreshToken()).isEqualTo("r2");
    }

    @Test
    void lateChallengeForAReplacedTokenDoesNotRefreshAgain() throws Exception {
        var delegate = new ScriptedMcpFetch()
                .json(TOKEN, 200, "{\"access_token\":\"a2\",\"refresh_token\":\"r2\","
                        + "\"token_type\":\"Bearer\"}");
        var provider = provider();
        var auth = OAuthProviders.adaptOAuthProvider(provider);

        challenge(auth, delegate, "a1");
        assertThat(delegate.sentTo(TOKEN)).hasSize(1);

        // A late 401 for a request that still carried the old token must not refresh again.
        challenge(auth, delegate, "a1");
        assertThat(delegate.sentTo(TOKEN)).hasSize(1);
        assertThat(auth.token()).isEqualTo("a2");
    }

    @Test
    void insufficientScopeRunsAStepUpWithoutRefreshing() throws Exception {
        var delegate = new ScriptedMcpFetch();
        var provider = provider();
        provider.tokenSet = new OAuthTokens("a1", "Bearer", null, "repo read:org", "r1", null);
        var auth = OAuthProviders.adaptOAuthProvider(provider);

        try {
            auth.onUnauthorized(new AuthProvider.Context(403,
                    "Bearer error=\"insufficient_scope\", scope=\"admin\"",
                    OAuthFlowTest.SERVER.toString(), "a1", delegate));
            throw new AssertionError("expected a redirect");
        } catch (McpOAuthAuthorizationRequiredError expected) {
            // The challenge may list only the missing scopes; the new grant keeps the old ones.
            assertThat(provider.authorizationUrl).isNotNull();
            var params = OAuthEndpoints.parseForm(provider.authorizationUrl.getRawQuery());
            assertThat(params).containsEntry("scope", "repo read:org admin");
        }
        // The working grant is kept until the user authorizes the new scope.
        assertThat(provider.tokens().accessToken()).isEqualTo("a1");
        assertThat(delegate.sentTo(TOKEN)).isEmpty();
    }

    private static void challenge(AuthProvider auth, McpFetch fetch, String token) {
        try {
            auth.onUnauthorized(new AuthProvider.Context(401, "Bearer",
                    OAuthFlowTest.SERVER.toString(), token, fetch));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void challenge(AuthProvider auth, McpFetch fetch, String token,
                                  java.util.List<Throwable> failures) {
        try {
            challenge(auth, fetch, token);
        } catch (Throwable error) {
            failures.add(error);
        }
    }

    private static TestOAuthProvider provider() {
        var provider = new TestOAuthProvider("http://127.0.0.1/callback");
        provider.client = new OAuthClientInformation("client", null, null, null, null,
                java.util.List.of(), null);
        provider.discovery = new OAuthDiscoveryState(AS,
                OAuthFlowTest.metadata(OAuthFlowTest.asJson(AS, null)), null, null);
        provider.tokenSet = new OAuthTokens("a1", "Bearer", null, "org:read", "r1", null);
        return provider;
    }

    private static McpFetch slowToken(ScriptedMcpFetch delegate, AtomicInteger entries,
                                       CountDownLatch firstInToken, CountDownLatch secondInToken,
                                       CountDownLatch release) {
        return request -> {
            if (TOKEN.equals(request.url().toString())) {
                if (entries.incrementAndGet() >= 2) {
                    secondInToken.countDown();
                } else {
                    firstInToken.countDown();
                }
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", error);
                }
            }
            return delegate.fetch(request);
        };
    }
}
