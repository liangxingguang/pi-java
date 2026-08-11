package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.protocol.MistralConversationsApi;

/**
 * Mistral provider — Mistral models via the Chat Completions API.
 */
public final class MistralProvider implements Provider {

    @Override
    public String name() { return "mistral"; }

    @Override
    public String displayName() { return "Mistral"; }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ChatApi.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        if (apiType.equals(ChatApi.class)) {
            return (T) new MistralConversationsApi(options);
        }
        throw new IllegalArgumentException("Unsupported API type: " + apiType);
    }

    @Override
    public ModelCatalog builtinModels() {
        return com.pijava.ai.catalog.BuiltinCatalog.mistralModels();
    }
}
