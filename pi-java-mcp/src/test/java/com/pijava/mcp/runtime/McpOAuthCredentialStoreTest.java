package com.pijava.mcp.runtime;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.oauth.McpOAuthState;
import com.pijava.mcp.oauth.OAuthTokens;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code mcp-auth.json} 的按 server 视图（{@code oauth.ts:111-221}）。
 */
class McpOAuthCredentialStoreTest {

    private static final String SERVER_URL = "http://example.test/mcp";

    /** pi keys by the namespace plus the normalized URL ({@code oauth.ts:127-130}). */
    private static final String KEY = "mcp__files|" + SERVER_URL;

    private final InMemoryAuthJsonBackend backend = new InMemoryAuthJsonBackend();

    private McpOAuthCredentialStore store() {
        return new McpOAuthCredentialStore(backend, null);
    }

    private static McpOAuthState state(String accessToken, String refreshToken) {
        return new McpOAuthState(SERVER_URL, null,
                new OAuthTokens(accessToken, "Bearer", 3600, null, refreshToken, null),
                1_700_000_000_000L, null, null, null);
    }

    @Test
    void savesAndLoadsOneServersState() {
        var store = store();
        store.forServer("files", SERVER_URL).save(state("a", "r"));
        assertThat(store.forServer("files", SERVER_URL).load().tokens().accessToken()).isEqualTo("a");
        assertThat(store.forServer("files", SERVER_URL).load().tokens().refreshToken()).isEqualTo("r");
    }

    @Test
    void writesJavascriptShapedJsonWithATrailingNewline() {
        store().forServer("files", SERVER_URL).save(state("a", "r"));
        var content = backend.content();
        assertThat(content).startsWith("{\n  \"mcp__files|" + SERVER_URL + "\": {\n    \"serverUrl\": ");
        assertThat(content).endsWith("}\n");
        assertThat(content).contains("\n    \"tokens\": {");
    }

    @Test
    void omitsAbsentStateFieldsTheWayJavascriptOmitsUndefined() {
        store().forServer("files", SERVER_URL).save(state("a", null));
        assertThat(backend.content())
                .doesNotContain("\"clientInformation\"")
                .doesNotContain("\"oauthState\"")
                .doesNotContain("\"refreshToken\"")
                .contains("\"tokensExpireAt\": 1700000000000");
    }

    @Test
    void firstLoadTakesOverLegacyStateAndDropsTheOldKey() {
        var legacyKey = SERVER_URL;
        backend.withLock(current -> AuthJsonBackend.LockResult.writing(null,
                "{\"" + legacyKey + "\": {\"serverUrl\": \"" + SERVER_URL
                        + "\", \"tokens\": {\"access_token\": \"old\", \"token_type\": \"Bearer\"}}}"));
        var store = store();

        assertThat(store.forServer("files", SERVER_URL).load().tokens().accessToken()).isEqualTo("old");
        var content = backend.content();
        assertThat(content).contains(KEY).doesNotContain("\"" + legacyKey + "\":");
    }

    @Test
    void aLaterLoadDoesNotTakeLegacyStateOverAgain() {
        backend.withLock(current -> AuthJsonBackend.LockResult.writing(null,
                "{\"" + SERVER_URL + "\": {\"serverUrl\": \"" + SERVER_URL
                        + "\", \"tokens\": {\"access_token\": \"old\", \"token_type\": \"Bearer\"}}}"));
        var store = store();
        store.forServer("files", SERVER_URL).save(state("mine", "r"));

        assertThat(store.forServer("files", SERVER_URL).load().tokens().accessToken()).isEqualTo("mine");
    }

    @Test
    void readingTokensDoesNotTakeLegacyStateOver() {
        backend.withLock(current -> AuthJsonBackend.LockResult.writing(null,
                "{\"" + SERVER_URL + "\": {\"serverUrl\": \"" + SERVER_URL
                        + "\", \"tokens\": {\"access_token\": \"old\", \"token_type\": \"Bearer\"}}}"));
        var store = store();

        assertThat(store.tokens("files", SERVER_URL).accessToken()).isEqualTo("old");
        assertThat(backend.content()).contains("\"" + SERVER_URL + "\":");
    }

    @Test
    void tokensAreNullWithoutStoredState() {
        assertThat(store().tokens("files", SERVER_URL)).isNull();
    }

    @Test
    void removesTheServerKey() {
        var store = store();
        store.forServer("files", SERVER_URL).save(state("a", "r"));
        assertThat(store.remove("files", SERVER_URL)).isTrue();
        assertThat(backend.content()).isEqualTo("{}\n");
    }

    @Test
    void removesLegacyStateTheServerWouldTakeOver() {
        backend.withLock(current -> AuthJsonBackend.LockResult.writing(null,
                "{\"" + SERVER_URL + "\": {\"serverUrl\": \"" + SERVER_URL + "\"}}"));
        var store = store();
        assertThat(store.remove("files", SERVER_URL)).isTrue();
        assertThat(backend.content()).isEqualTo("{}\n");
    }

    @Test
    void reportsNothingToRemove() {
        assertThat(store().remove("files", SERVER_URL)).isFalse();
    }

    @Test
    void normalizesTheServerUrlIntoTheLegacyKey() {
        // WHATWG URL lower-cases the host and drops the scheme's default port.
        var backend2 = new InMemoryAuthJsonBackend();
        var store = new McpOAuthCredentialStore(backend2, null);
        store.forServer("files", "HTTP://Example.TEST:80/mcp").save(state("a", "r"));
        assertThat(backend2.content()).contains("\"mcp__files|http://example.test/mcp\"");
    }

    @Test
    void withoutALockDirectoryRefreshesAreNotSerializedAcrossProcesses() throws Exception {
        // The in-process single flight is the only guard then (oauth.ts:173).
        var store = store();
        assertThat(store.forServer("files", SERVER_URL).withRefreshLock(() -> "ran")).isEqualTo("ran");
    }
}
