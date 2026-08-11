package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.protocol.OpenAICompletionsApi;

/**
 * DeepSeek provider — reuses the OpenAI adapter with a different base URL.
 */
public final class DeepSeekProvider implements Provider {

    @Override
    public String name() { return "deepseek"; }

    @Override
    public String displayName() { return "DeepSeek"; }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ChatApi.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        if (apiType.equals(ChatApi.class)) {
            var opts = new ApiOptions(
                    options.baseUrl() != null && !options.baseUrl().isBlank()
                            ? options.baseUrl() : "https://api.deepseek.com/v1",
                    options.apiKey(),
                    options.timeout(),
                    options.maxRetries(),
                    options.extra());
            return (T) new OpenAICompletionsApi(opts, "DEEPSEEK_API_KEY");
        }
        throw new IllegalArgumentException("Unsupported API type: " + apiType);
    }

    @Override
    public ModelCatalog builtinModels() {
        return com.pijava.ai.catalog.BuiltinCatalog.deepseekModels();
    }
}
