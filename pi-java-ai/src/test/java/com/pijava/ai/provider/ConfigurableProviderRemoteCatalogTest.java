package com.pijava.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.RemoteCatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ConfigurableProvider#builtinModels()} picks a runtime
 * {@link RemoteCatalog} when {@code modelsUrl} is set, otherwise the static
 * builtin catalog, and shares a process-wide RemoteCatalog across provider
 * instances. Construction does not fetch — only {@code listModels()} would.
 */
class ConfigurableProviderRemoteCatalogTest {

    private static final String TEST_URL = "https://example.invalid/models.json";

    @AfterEach
    void clearCache() {
        ConfigurableProvider.clearRemoteCatalogCache();
    }

    @Test
    void returnsStaticCatalogWhenNoModelsUrl() {
        var provider = new TestProvider(null);

        assertThat(provider.builtinModels())
            .isNotNull()
            .isNotInstanceOf(RemoteCatalog.class)
            .satisfies(m -> assertThat(m.listModels())
                .extracting(x -> x.id().modelName())
                .contains("deepseek-v4-flash"));
    }

    @Test
    void returnsRemoteCatalogWhenModelsUrlSet() {
        var provider = new TestProvider(TEST_URL);

        assertThat(provider.builtinModels()).isInstanceOf(RemoteCatalog.class);
    }

    @Test
    void sharesRemoteCatalogAcrossInstances() {
        var first = new TestProvider(TEST_URL).builtinModels();
        var second = new TestProvider(TEST_URL).builtinModels();

        assertThat(first).isInstanceOf(RemoteCatalog.class);
        assertThat(second).isSameAs(first);
    }

    /** Minimal ConfigurableProvider with a configurable modelsUrl. */
    private static final class TestProvider extends ConfigurableProvider {

        private final String modelsUrl;

        TestProvider(String modelsUrl) {
            this.modelsUrl = modelsUrl;
        }

        @Override
        protected ProviderConfig config() {
            return new ProviderConfig(
                "test-remote", "Test Remote", "http://localhost", "TEST_API_KEY",
                Protocol.OPENAI_COMPLETIONS, Set.of(Protocol.OPENAI_COMPLETIONS),
                BuiltinCatalog.deepseekModels(), modelsUrl);
        }

        @Override
        protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}
