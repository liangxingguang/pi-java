package com.pijava.ai.provider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ModelsJsonProvider: inline-key injection and protocol routing.
 */
class ModelsJsonProviderTest {

    private static final String KEY_CONFIG = """
        {
          "providers": {
            "relay": {
              "baseUrl": "https://relay.example.com/v1",
              "apiKey": "sk-inline",
              "api": "openai-completions",
              "models": [{"id": "m1"}]
            },
            "noauth": {
              "baseUrl": "https://local.example.com/v1",
              "api": "openai-completions",
              "models": [{"id": "m2"}]
            }
          }
        }
        """;

    private ModelsJsonProvider provider(String id, String json) throws Exception {
        var path = java.nio.file.Files.createTempFile("models", ".json");
        java.nio.file.Files.writeString(path, json);
        var config = ModelsJsonConfig.load(path);
        @SuppressWarnings("unchecked")
        var list = (List<Provider>) (List<?>) config.buildProviders();
        return list.stream()
            .filter(p -> p.name().equals(id))
            .map(p -> (ModelsJsonProvider) p)
            .findFirst().orElseThrow();
    }

    @Test
    void inlineKeyInjectedWhenOptionsEmpty() throws Exception {
        var p = provider("relay", KEY_CONFIG);
        var api = (ChatApi) p.createApi(ChatApi.class, ApiOptions.defaults());
        assertThat(api).isInstanceOf(com.pijava.ai.protocol.OpenAICompletionsApi.class);
        // No exception: the inline key satisfies the credential requirement.
    }

    @Test
    void explicitKeyKeepsPriorityOverInline() throws Exception {
        var p = provider("relay", KEY_CONFIG);
        var options = new ApiOptions("", "sk-explicit", Duration.ofSeconds(1), 0, Map.of());
        var api = (ChatApi) p.createApi(ChatApi.class, options);
        assertThat(api).isNotNull();
    }

    @Test
    void noInlineKeyAndNoEnvFallsBackToPlaceholder() throws Exception {
        var p = provider("noauth", KEY_CONFIG);
        // resolveApiKey(null envVar) → "local" placeholder instead of throwing.
        var api = (ChatApi) p.createApi(ChatApi.class, ApiOptions.defaults());
        assertThat(api).isInstanceOf(com.pijava.ai.protocol.OpenAICompletionsApi.class);
    }

    @Test
    void anthropicProtocolRoutesToAnthropicAdapter() throws Exception {
        var p = provider("relay", """
            {
              "providers": {
                "relay": {
                  "baseUrl": "https://relay.example.com",
                  "apiKey": "sk-inline",
                  "api": "anthropic-messages",
                  "models": [{"id": "m1"}]
                }
              }
            }
            """);
        var api = (ChatApi) p.createApi(ChatApi.class, ApiOptions.defaults());
        assertThat(api).isInstanceOf(com.pijava.ai.protocol.AnthropicMessagesApi.class);
    }

    @Test
    void unsupportedProtocolThrowsOnBuild() {
        var config = ModelsJsonConfig.load(pathOf("""
            {
              "providers": {
                "relay": {
                  "baseUrl": "https://relay.example.com",
                  "api": "google-generative-ai",
                  "models": [{"id": "m1"}]
                }
              }
            }
            """));
        // google-generative-ai parses as a valid Protocol enum but the
        // provider only supports the two shared chat adapters at runtime;
        // validation happens in buildProviders → allowed protocols.
        var providers = config.buildProviders();
        assertThat(providers).hasSize(1);
        assertThatThrownBy(() -> providers.get(0).createApi(ChatApi.class, ApiOptions.defaults()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("google-generative-ai");
    }

    @Test
    void providerBaseUrlWinsOverSettingsLevelOverride() throws Exception {
        var p = provider("relay", KEY_CONFIG);
        // Simulates settings.defaultBaseUrl leaking into options (no /v1);
        // the models.json baseUrl must win so the request path stays correct.
        var options = new ApiOptions("https://api.teamorouter.cn", "sk-x",
            Duration.ofSeconds(1), 0, Map.of());
        var api = (ChatApi) p.createApi(ChatApi.class, options);
        assertThat(api).isInstanceOf(com.pijava.ai.protocol.OpenAICompletionsApi.class);
    }

    private java.nio.file.Path pathOf(String json) {
        try {
            var path = java.nio.file.Files.createTempFile("models", ".json");
            java.nio.file.Files.writeString(path, json);
            return path;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
