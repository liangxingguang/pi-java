package com.pijava.ai.auth;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-17: OAuthCredentialStore — provider → OAuthCredential JSON 持久化。
 */
class OAuthCredentialStoreTest {

    @TempDir
    Path tmp;

    @Test
    void storeResolveDeleteRoundTrip() throws Exception {
        var store = new OAuthCredentialStore(tmp.resolve("auth-oauth.json"));

        assertThat(store.resolve("openrouter")).isEmpty();

        var credential = OAuthCredential.permanent("pk-123");
        store.store("openrouter", credential);

        assertThat(store.resolve("openrouter")).hasValue(credential);

        store.delete("openrouter");
        assertThat(store.resolve("openrouter")).isEmpty();
    }

    @Test
    void storesExpiringCredentialWithRefresh() {
        var store = new OAuthCredentialStore(tmp.resolve("auth-oauth.json"));
        var credential = new OAuthCredential("access", "refresh", 1_700_000_000L, null);

        store.store("x", credential);

        assertThat(store.resolve("x").orElseThrow().refreshToken()).isEqualTo("refresh");
        assertThat(store.resolve("x").orElseThrow().isExpired()).isTrue();
    }

    @Test
    void chatGptCredentialRoundTripsIssuedClientIdAndScopes() {
        var store = new OAuthCredentialStore(tmp.resolve("auth-oauth.json"));
        var credential = new OAuthCredential("access", "refresh", 1_800_000_000L, null,
            "oaiapp_issued", java.util.List.of("openid", "chatgpt.tokens.use.direct"));

        store.store("openai", credential);

        var read = store.resolve("openai").orElseThrow();
        assertThat(read.clientId()).isEqualTo("oaiapp_issued");
        assertThat(read.scopes()).containsExactly("openid", "chatgpt.tokens.use.direct");
    }

    @Test
    void readsLegacyFileWithoutClientIdAndScopes() throws Exception {
        // 批 7 前落盘的凭证只有四字段；新代码必须读回（两新字段为 null/空）。
        var path = tmp.resolve("legacy.json");
        java.nio.file.Files.writeString(path, """
            {"anthropic":{"accessToken":"a","refreshToken":"r","expiresAtEpochSec":1800000000,"baseUrl":null}}
            """);
        var store = new OAuthCredentialStore(path);

        var read = store.resolve("anthropic").orElseThrow();
        assertThat(read.accessToken()).isEqualTo("a");
        assertThat(read.clientId()).isNull();
        assertThat(read.scopes()).isEmpty();
    }

    @Test
    void missingFileResolvesEmpty() {
        var store = new OAuthCredentialStore(tmp.resolve("none.json"));
        assertThat(store.resolve("any")).isEmpty();
        store.delete("any"); // 不抛异常
    }

    // ── 包 B1 R2：原子 modify ────────────────────────────────────────────

    @Test
    void modifyAppliesAndPersistsUnderLock() {
        var store = new OAuthCredentialStore(tmp.resolve("auth-oauth.json"));
        store.store("anthropic", new OAuthCredential("old", "rt", 1_700_000_000L, null));

        var result = store.modify("anthropic", current ->
            new OAuthCredential("new", current.refreshToken(), current.expiresAtEpochSec(), current.baseUrl()));

        assertThat(result.accessToken()).isEqualTo("new");
        assertThat(store.resolve("anthropic").orElseThrow().accessToken()).isEqualTo("new");
    }

    @Test
    void modifyOnMissingProviderReturnsNullWithoutCallingMutation() {
        var store = new OAuthCredentialStore(tmp.resolve("auth-oauth.json"));

        var result = store.modify("ghost", current -> {
            throw new AssertionError("must not be called for absent provider");
        });

        assertThat(result).isNull();
    }
}
