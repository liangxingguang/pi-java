package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.protocol.OpenAICompletionsApi;

/**
 * OpenAI provider — GPT models via the Chat Completions API.
 */
public final class OpenAIProvider implements Provider {

    @Override
    public String name() { return "openai"; }

    @Override
    public String displayName() { return "OpenAI"; }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ChatApi.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        if (apiType.equals(ChatApi.class)) {
            return (T) new OpenAICompletionsApi(options);
        }
        throw new IllegalArgumentException("Unsupported API type: " + apiType);
    }

    @Override
    public ModelCatalog builtinModels() {
        return com.pijava.ai.catalog.BuiltinCatalog.openaiModels();
    }
}
