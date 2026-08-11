package com.pijava.ai.auth;

import com.pijava.ai.api.ApiOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

/**
 * Tests for the authentication system: API key resolution and
 * {@link FileCredentialStore}.
 */
class AuthTest {

    @Test
    void apiOptionsApiKeyShouldHaveHighestPriority() {
        // Protocol adapters check ApiOptions.apiKey() first,
        // then fall back to environment variables.
        assertThat(resolveKey("explicit-key", "NONEXISTENT_ENV"))
                .isEqualTo("explicit-key");
    }

    @Test
    void shouldFallbackToEnvVar() {
        // PATH environment variable always exists
        assertThat(resolveKey("", "PATH")).isNotEmpty();
    }

    @Test
    void shouldThrowWhenNoKeyAvailable() {
        assertThatThrownBy(() -> resolveKey("", "NONEXISTENT_ENV_VAR_12345"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void fileCredentialStoreShouldStoreAndResolve(@TempDir Path tempDir) {
        var store = new FileCredentialStore(tempDir.resolve("auth.json"));

        store.storeApiKey("anthropic", "sk-ant-test123");
        store.storeApiKey("openai", "sk-openai-test456");

        assertThat(store.resolveApiKey("anthropic")).hasValue("sk-ant-test123");
        assertThat(store.resolveApiKey("openai")).hasValue("sk-openai-test456");
        assertThat(store.resolveApiKey("google")).isEmpty();
    }

    @Test
    void fileCredentialStoreShouldDeleteKeys(@TempDir Path tempDir) {
        var store = new FileCredentialStore(tempDir.resolve("auth.json"));

        store.storeApiKey("anthropic", "key1");
        assertThat(store.resolveApiKey("anthropic")).hasValue("key1");

        store.deleteApiKey("anthropic");
        assertThat(store.resolveApiKey("anthropic")).isEmpty();
    }

    @Test
    void fileCredentialStoreShouldOverwriteExisting(@TempDir Path tempDir) {
        var store = new FileCredentialStore(tempDir.resolve("auth.json"));

        store.storeApiKey("anthropic", "old-key");
        store.storeApiKey("anthropic", "new-key");

        assertThat(store.resolveApiKey("anthropic")).hasValue("new-key");
    }

    // Helper: replicate the key resolution logic used by protocol adapters
    private static String resolveKey(String explicitKey, String envVar) {
        if (explicitKey != null && !explicitKey.isBlank()) return explicitKey;
        var env = System.getenv(envVar);
        if (env != null && !env.isBlank()) return env;
        throw new IllegalStateException("No API key. Set " + envVar + " or pass apiKey.");
    }
}
