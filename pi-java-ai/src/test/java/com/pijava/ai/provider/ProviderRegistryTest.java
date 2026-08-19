package com.pijava.ai.provider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for built-in loading and ServiceLoader discovery.
 */
class ProviderRegistryTest {

    @Test
    void loadBuiltinProvidersRegistersSixteen() {
        var registry = ProviderRegistry.create();
        assertThat(registry.loadBuiltinProviders()).isEqualTo(16);
        assertThat(registry.listAll()).hasSize(16);
        assertThat(registry.get("minimax-cn")).isPresent();
        assertThat(registry.get("ollama")).isPresent();
    }

    @Test
    void listByProtocolFindsAnthropicCompatibleProviders() {
        var registry = ProviderRegistry.create();
        registry.loadBuiltinProviders();
        var names = registry.listByProtocol(Protocol.ANTHROPIC_MESSAGES).stream()
            .map(Provider::name)
            .toList();
        assertThat(names).contains("anthropic", "minimax", "minimax-cn");
        assertThat(names).doesNotContain("openai", "ollama");
    }

    @Test
    void discoverFromServiceLoaderRegistersTestFactory() {
        var registry = ProviderRegistry.create();
        registry.discoverFromServiceLoader();
        assertThat(registry.get("test-discovered")).isPresent();
    }

    @Test
    void builtinRegistrationWinsOverServiceLoaderDuplicate() {
        var registry = ProviderRegistry.create();
        registry.loadBuiltinProviders();
        registry.discoverFromServiceLoader();
        assertThat(registry.get("anthropic")).isPresent();
        assertThat(registry.get("anthropic").orElseThrow())
            .isInstanceOf(AnthropicProvider.class);
        assertThat(registry.get("test-discovered")).isPresent();
    }
}
