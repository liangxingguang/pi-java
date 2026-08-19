package com.pijava.ai.provider;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.protocol.MistralConversationsApi;

/**
 * Mistral provider — Mistral models via the Chat Completions API.
 */
public final class MistralProvider extends ConfigurableProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "mistral", "Mistral", "https://api.mistral.ai/v1",
            "MISTRAL_API_KEY", Protocol.MISTRAL_CONVERSATIONS,
            BuiltinCatalog.mistralModels());
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            case MISTRAL_CONVERSATIONS -> new MistralConversationsApi(options);
            default -> throw new IllegalArgumentException(
                "MistralProvider cannot serve protocol " + protocol);
        };
    }
}
