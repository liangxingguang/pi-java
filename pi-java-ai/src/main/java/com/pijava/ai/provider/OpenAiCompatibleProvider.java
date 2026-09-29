package com.pijava.ai.provider;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.protocol.OpenAICompletionsApi;

/**
 * OpenAI-compatible Provider base class.
 *
 * <p>Most providers (Moonshot, Qwen, Zhipu, Ollama, etc.) expose an
 * OpenAI Chat Completions-compatible endpoint. They all share this
 * base class, which constructs an {@link OpenAICompletionsApi} with
 * the provider's specific baseUrl and API key env var.</p>
 */
public abstract class OpenAiCompatibleProvider extends ConfigurableProvider {

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            case OPENAI_COMPLETIONS -> {
                var envVar = config().apiKeyEnvVar();
                if (envVar == null) {
                    yield new OpenAICompletionsApi(withPlaceholderKey(options), "OLLAMA_API_KEY");
                }
                yield new OpenAICompletionsApi(options, envVar);
            }
            default -> throw new IllegalArgumentException(
                "OpenAiCompatibleProvider cannot serve protocol " + protocol);
        };
    }

    /**
     * Local unauthenticated endpoints (Ollama) still need a non-blank key
     * because {@link OpenAICompletionsApi} rejects empty credentials.
     */
    private static ApiOptions withPlaceholderKey(ApiOptions options) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options;
        }
        // 六参（包 A-02，B138）：占位 key 不改凭证形态。
        return new ApiOptions(
            options.baseUrl(), "ollama",
            options.timeout(), options.maxRetries(), options.extra(), options.authKind());
    }
}
