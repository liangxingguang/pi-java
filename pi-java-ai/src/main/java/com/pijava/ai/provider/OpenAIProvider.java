package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.EmbeddingApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.protocol.OpenAICompletionsApi;
import com.pijava.ai.protocol.OpenAIEmbeddingApi;
import com.pijava.ai.protocol.OpenAIResponsesApi;

/**
 * OpenAI provider — GPT models via Chat Completions (default) or
 * OpenAI Responses (opt-in via {@code extra.protocol = "openai-responses"}).
 * P6-28: also serves {@link EmbeddingApi} via {@code /v1/embeddings}.
 */
public final class OpenAIProvider extends ConfigurableProvider {

    @Override
    protected ProviderConfig config() {
        return new ProviderConfig(
            "openai", "OpenAI", "https://api.openai.com/v1",
            "OPENAI_API_KEY", Protocol.OPENAI_COMPLETIONS,
            Set.of(Protocol.OPENAI_COMPLETIONS, Protocol.OPENAI_RESPONSES),
            BuiltinCatalog.openaiModels());
    }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ChatApi.class, EmbeddingApi.class);
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            case OPENAI_COMPLETIONS -> new OpenAICompletionsApi(options, config().apiKeyEnvVar());
            case OPENAI_RESPONSES -> new OpenAIResponsesApi(options, config().apiKeyEnvVar());
            default -> throw new IllegalArgumentException(
                "OpenAIProvider cannot serve protocol " + protocol);
        };
    }

    @Override
    protected EmbeddingApi createEmbeddingApi(Protocol protocol, ApiOptions options) {
        return new OpenAIEmbeddingApi(options, config().apiKeyEnvVar());
    }
}
