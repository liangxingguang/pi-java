package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.catalog.ModelCatalog;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ProviderConfig} validation and convenience constructors.
 */
class ProviderConfigTest {

    @Test
    void singleProtocolConfigIsSelfConsistent() {
        var config = ProviderConfig.single(
            "ollama", "Ollama", "http://localhost:11434/v1",
            null, Protocol.OPENAI_COMPLETIONS, ModelCatalog.empty());

        assertThat(config.name()).isEqualTo("ollama");
        assertThat(config.apiKeyEnvVar()).isNull();
        assertThat(config.defaultProtocol()).isEqualTo(Protocol.OPENAI_COMPLETIONS);
        assertThat(config.supportedProtocols()).containsExactly(Protocol.OPENAI_COMPLETIONS);
    }

    @Test
    void blankApiKeyEnvVarNormalizesToNull() {
        var config = ProviderConfig.single(
            "local", "Local", "http://localhost",
            "  ", Protocol.OPENAI_COMPLETIONS, ModelCatalog.empty());
        assertThat(config.apiKeyEnvVar()).isNull();
    }

    @Test
    void defaultProtocolMustBeSupported() {
        assertThatThrownBy(() -> new ProviderConfig(
            "x", "X", "http://x", "X_KEY",
            Protocol.OPENAI_RESPONSES,
            Set.of(Protocol.OPENAI_COMPLETIONS),
            ModelCatalog.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("defaultProtocol");
    }

    @Test
    void protocolWireNameIsKebabCase() {
        assertThat(Protocol.OPENAI_COMPLETIONS.wireName()).isEqualTo("openai-completions");
        assertThat(Protocol.ANTHROPIC_MESSAGES.wireName()).isEqualTo("anthropic-messages");
    }
}
