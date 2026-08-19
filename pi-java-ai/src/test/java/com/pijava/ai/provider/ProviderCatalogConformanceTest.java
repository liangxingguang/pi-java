package com.pijava.ai.provider;

import java.time.Duration;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.protocol.AnthropicMessagesApi;
import com.pijava.ai.protocol.OpenAICompletionsApi;
import com.pijava.ai.provider.builtin.OllamaProvider;
import com.pijava.ai.provider.builtin.ProviderCatalog;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance checks for every built-in provider configuration.
 */
class ProviderCatalogConformanceTest {

    private static final String[] EXPECTED_NAMES = {
        "anthropic", "openai", "google", "deepseek", "mistral",
        "moonshotai-cn", "moonshotai", "zai-coding-cn", "zai",
        "qwen-token-plan-cn", "xiaomi", "xiaomi-token-plan-cn",
        "minimax-cn", "minimax", "ant-ling", "ollama"
    };

    @Test
    void catalogContainsSixteenProviders() {
        var names = ProviderCatalog.all().stream().map(Provider::name).toList();
        assertThat(names).containsExactly(EXPECTED_NAMES);
    }

    @Test
    void everyProviderHasSelfConsistentConfig() {
        for (var provider : ProviderCatalog.all()) {
            assertThat(provider.name()).isNotBlank();
            assertThat(provider.displayName()).isNotBlank();
            assertThat(provider.supportedProtocols()).isNotEmpty();
            assertThat(provider.builtinModels().listModels()).isNotEmpty();
            assertThat(provider).isInstanceOf(ConfigurableProvider.class);

            var config = ((ConfigurableProvider) provider).providerConfig();
            assertThat(config.defaultBaseUrl()).isNotBlank();
            assertThat(config.supportedProtocols()).contains(config.defaultProtocol());
            if (!"ollama".equals(provider.name())) {
                assertThat(config.apiKeyEnvVar())
                    .as(provider.name() + " apiKeyEnvVar")
                    .isNotBlank();
            }
        }
    }

    @Test
    void minimaxUsesAnthropicMessagesAndChinaBaseUrl() {
        var miniMaxCn = provider("minimax-cn");
        assertThat(miniMaxCn.supportedProtocols()).containsExactly(Protocol.ANTHROPIC_MESSAGES);
        assertThat(((ConfigurableProvider) miniMaxCn).providerConfig().defaultBaseUrl())
            .isEqualTo("https://api.minimaxi.com/anthropic");

        var api = miniMaxCn.createApi(ChatApi.class, keyedOptions());
        assertThat(api).isInstanceOf(AnthropicMessagesApi.class);
    }

    @Test
    void ollamaCreatesApiWithoutApiKey() {
        var ollama = new OllamaProvider();
        assertThat(ollama.providerConfig().apiKeyEnvVar()).isNull();
        var api = ollama.createApi(ChatApi.class, ApiOptions.defaults());
        assertThat(api).isInstanceOf(OpenAICompletionsApi.class);
    }

    @Test
    void extraProtocolCanSelectDefaultAndRejectsUnsupported() {
        var deepseek = provider("deepseek");
        var ok = new ApiOptions("", "sk-test", Duration.ofSeconds(1), 0,
            Map.of("protocol", "openai-completions"));
        assertThat(deepseek.createApi(ChatApi.class, ok))
            .isInstanceOf(OpenAICompletionsApi.class);

        var bad = new ApiOptions("", "sk-test", Duration.ofSeconds(1), 0,
            Map.of("protocol", "anthropic-messages"));
        assertThatThrownBy(() -> deepseek.createApi(ChatApi.class, bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not support");
    }

    @Test
    void openAiCompatibleProvidersShareCompletionsAdapter() {
        for (var name : new String[] {
            "moonshotai-cn", "moonshotai", "zai-coding-cn", "zai",
            "qwen-token-plan-cn", "xiaomi", "xiaomi-token-plan-cn",
            "ant-ling", "ollama", "openai", "deepseek"
        }) {
            var provider = provider(name);
            assertThat(provider).isInstanceOf(OpenAiCompatibleProvider.class);
            assertThat(provider.createApi(ChatApi.class, keyedOptions()))
                .isInstanceOf(OpenAICompletionsApi.class);
        }
    }

    private static Provider provider(String name) {
        return ProviderCatalog.all().stream()
            .filter(p -> p.name().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static ApiOptions keyedOptions() {
        return new ApiOptions("", "sk-test", Duration.ofSeconds(1), 0, Map.of());
    }
}
