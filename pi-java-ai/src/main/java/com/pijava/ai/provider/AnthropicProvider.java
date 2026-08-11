package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.protocol.AnthropicMessagesApi;

/**
 * Anthropic provider — Claude models via the Anthropic Messages API.
 */
public final class AnthropicProvider implements Provider {

    @Override
    public String name() { return "anthropic"; }

    @Override
    public String displayName() { return "Anthropic"; }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ChatApi.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        if (apiType.equals(ChatApi.class)) {
            return (T) new AnthropicMessagesApi(options);
        }
        throw new IllegalArgumentException("Unsupported API type: " + apiType);
    }

    @Override
    public ModelCatalog builtinModels() {
        return com.pijava.ai.catalog.BuiltinCatalog.anthropicModels();
    }
}
