package com.pijava.mcp.oauth;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Default provider state handling (pi provider.ts, oauth.test.ts clause 4). */
class McpOAuthProviderTest {

    private static McpOAuthProvider provider(String serverUrl, OAuthStateStore store) {
        return new McpOAuthProvider(new McpOAuthProviderOptions(
                URI.create(serverUrl),
                URI.create("http://127.0.0.1/callback"),
                new OAuthClientMetadata(null, null, null, null, "test",
                        null, null, null, null, null, null, null, null, null, null, null),
                null, null, null, store, url -> { }));
    }

    @Test
    void bindsPersistedCredentialsToTheExactServerUrl() {
        var store = new MemoryOAuthStateStore();
        var first = provider("https://one.example/mcp", store);
        first.saveTokens(new OAuthTokens("secret", "Bearer", null, null, null, null));
        assertThat(first.tokens().accessToken()).isEqualTo("secret");

        var second = provider("https://two.example/mcp", store);
        assertThat(second.tokens()).isNull();
    }

    @Test
    void fillsClientMetadataDefaults() {
        var metadata = provider("https://one.example/mcp", new MemoryOAuthStateStore())
                .clientMetadata();
        assertThat(metadata.redirectUris()).containsExactly("http://127.0.0.1/callback");
        assertThat(metadata.grantTypes()).containsExactly("authorization_code", "refresh_token");
        assertThat(metadata.responseTypes()).containsExactly("code");
        assertThat(metadata.tokenEndpointAuthMethod()).isEqualTo("none");
    }

    @Test
    void recordsTokenExpiryFromExpiresIn() {
        var store = new MemoryOAuthStateStore();
        var provider = provider("https://one.example/mcp", store);
        provider.saveTokens(new OAuthTokens("a", "Bearer", 3600, null, "r", null));
        var before = System.currentTimeMillis();
        assertThat(store.load().tokensExpireAt())
                .isBetween(before + 3_500_000L, before + 3_700_000L);

        // A later save without expires_in drops the stored expiry.
        provider.saveTokens(new OAuthTokens("a", "Bearer", null, null, "r", null));
        assertThat(store.load().tokensExpireAt()).isNull();
    }

    @Test
    void invalidatesCredentialsByKind() {
        var store = new MemoryOAuthStateStore();
        var provider = provider("https://one.example/mcp", store);
        provider.saveTokens(new OAuthTokens("a", "Bearer", 60, null, "r", null));
        provider.saveCodeVerifier("verifier");
        provider.state();

        provider.invalidateCredentials("tokens");
        assertThat(store.load().tokens()).isNull();
        assertThat(store.load().tokensExpireAt()).isNull();
        assertThat(store.load().codeVerifier()).isEqualTo("verifier");

        provider.invalidateCredentials("all");
        assertThat(store.load().codeVerifier()).isNull();
        assertThat(store.load().oauthState()).isNull();
    }

    @Test
    void reusesAStoredStateAndGeneratesOneOtherwise() {
        var store = new MemoryOAuthStateStore();
        var provider = provider("https://one.example/mcp", store);
        var generated = provider.state();
        assertThat(generated).hasSize(64);
        assertThat(provider.state()).isEqualTo(generated);
    }

    @Test
    void codeVerifierIsRequired() {
        var provider = provider("https://one.example/mcp", new MemoryOAuthStateStore());
        assertThatThrownBy(provider::codeVerifier)
                .hasMessage("No OAuth PKCE code verifier is stored");
    }

    @Test
    void configuredClientSkipsRegistrationAndIsNotOverwritten() {
        var store = new MemoryOAuthStateStore();
        var provider = new McpOAuthProvider(new McpOAuthProviderOptions(
                URI.create("https://one.example/mcp"),
                URI.create("http://127.0.0.1/callback"),
                new OAuthClientMetadata(null, null, null, null, "test",
                        null, null, null, null, null, null, null, null, null, null, null),
                null, "configured", "secret", store, url -> { }));
        assertThat(provider.clientInformation().clientId()).isEqualTo("configured");
        assertThat(provider.canSaveClientInformation()).isFalse();
        provider.saveClientInformation(new OAuthClientInformation("other", null, null, null,
                null, List.of(), null));
        assertThat(provider.clientInformation().clientId()).isEqualTo("configured");
    }

    @Test
    void memoryStoreCopiesOnEveryBoundary() {
        var store = new MemoryOAuthStateStore();
        var state = new McpOAuthState("https://one.example/mcp", null,
                new OAuthTokens("a", "Bearer", null, null, null, null), null, null, null, null);
        store.save(state);
        var loaded = store.load();
        assertThat(loaded).isNotSameAs(state);
        assertThat(loaded.tokens()).isNotSameAs(state.tokens());
        assertThat(loaded.tokens().accessToken()).isEqualTo("a");
    }
}
