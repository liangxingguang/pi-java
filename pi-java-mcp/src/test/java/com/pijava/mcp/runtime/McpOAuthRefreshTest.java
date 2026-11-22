package com.pijava.mcp.runtime;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.AuthProvider;
import com.pijava.mcp.oauth.McpOAuthAuthorizationRequiredError;
import com.pijava.mcp.oauth.McpOAuthState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refreshing stored tokens never registers a client and never clobbers a pending sign-in
 * (pi {@code auth-provider.ts:101-130}, {@code test/auth-provider.test.ts:58-95}).
 */
class McpOAuthRefreshTest {

    private static final String CLIENT_NAME = "pi-java";

    @Test
    void refreshingWithoutAStoredClientDoesNotRegisterOne() throws Exception {
        try (var server = AuthorizationServerFixture.start(AuthorizationServerFixture.Mode.NORMAL)) {
            var store = new MemoryServerStore();
            signIn(server, store);
            assertThat(server.registrations()).isEqualTo(1);
            // The tokens outlived the client, for example because another process signed out.
            var stored = store.load();
            store.save(new McpOAuthState(stored.serverUrl(), null, stored.tokens(),
                    stored.tokensExpireAt(), stored.codeVerifier(), stored.oauthState(),
                    stored.discovery()));

            assertThatThrownBy(() -> provider(server, store).onUnauthorized(context(server, "first-token")))
                    .isInstanceOf(McpOAuthAuthorizationRequiredError.class);

            // Registering a client here would replace the sign-in the user is doing in the browser.
            assertThat(server.registrations()).isEqualTo(1);
            assertThat(server.refreshes()).isZero();
        }
    }

    @Test
    void aRejectedClientIsDroppedButThePendingSignInStays() throws Exception {
        try (var server = AuthorizationServerFixture.start(
                AuthorizationServerFixture.Mode.REJECT_CLIENT)) {
            var store = new MemoryServerStore();
            signIn(server, store);
            var pending = store.load();
            assertThat(pending.oauthState()).isNotNull();
            assertThat(pending.codeVerifier()).isNotNull();

            assertThatThrownBy(() -> provider(server, store).onUnauthorized(context(server, "first-token")))
                    .isInstanceOf(McpOAuthAuthorizationRequiredError.class);

            var after = store.load();
            assertThat(after.clientInformation()).isNull();
            assertThat(after.tokens()).isNull();
            // The pending authorization is the user's sign-in: its state and verifier survive.
            assertThat(after.oauthState()).isEqualTo(pending.oauthState());
            assertThat(after.codeVerifier()).isEqualTo(pending.codeVerifier());
            // The retry after the rejection must not register a client either.
            assertThat(server.registrations()).isEqualTo(1);
            assertThat(server.refreshes()).isEqualTo(1);
        }
    }

    @Test
    void aRejectedRefreshTokenDropsOnlyTheTokens() throws Exception {
        try (var server = AuthorizationServerFixture.start(
                AuthorizationServerFixture.Mode.REJECT_REFRESH)) {
            var store = new MemoryServerStore();
            signIn(server, store);
            var pending = store.load();

            assertThatThrownBy(() -> provider(server, store).onUnauthorized(context(server, "first-token")))
                    .isInstanceOf(McpOAuthAuthorizationRequiredError.class);

            var after = store.load();
            assertThat(after.tokens()).isNull();
            // The client is fine; only the grant is gone.
            assertThat(after.clientInformation().clientId()).isEqualTo("test-client");
            assertThat(after.oauthState()).isEqualTo(pending.oauthState());
            assertThat(after.codeVerifier()).isEqualTo(pending.codeVerifier());
            // One attempt only: without tokens the retry goes straight to the redirect result.
            assertThat(server.refreshes()).isEqualTo(1);
            assertThat(server.registrations()).isEqualTo(1);
        }
    }

    private static void signIn(AuthorizationServerFixture server, McpOAuthServerStore store)
            throws Exception {
        McpSignIn.signIn(new McpSignIn.Options(server.mcpUrl(), store, McpOAuthSettings.none(),
                null, new BrowserPrompt(), CLIENT_NAME));
    }

    private static McpAuthProvider provider(AuthorizationServerFixture server,
                                            McpOAuthServerStore store) {
        return McpAuthProviders.create(new McpAuthProviders.Options(
                URI.create(server.mcpUrl()), store, McpOAuthSettings::none, challenge -> {
                }, CLIENT_NAME));
    }

    private static AuthProvider.Context context(AuthorizationServerFixture server, String token) {
        return new AuthProvider.Context(401, "", server.mcpUrl(), token, null);
    }
}
