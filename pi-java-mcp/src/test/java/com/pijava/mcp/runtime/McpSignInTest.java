package com.pijava.mcp.runtime;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.oauth.McpOAuthState;
import com.pijava.mcp.oauth.OAuthClientInformation;
import com.pijava.mcp.oauth.OAuthTokens;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure parts of a sign-in: scope merging and the state a sign-in starts from
 * (pi {@code sign-in.ts:151-176}).
 */
class McpSignInTest {

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
}
