package com.pijava.ai.provider;

import com.pijava.ai.catalog.BuiltinCatalog;

/**
 * DeepSeek provider — reuses the OpenAI adapter with a different base URL.
 */
public final class DeepSeekProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            "DEEPSEEK_API_KEY", Protocol.OPENAI_COMPLETIONS,
            BuiltinCatalog.deepseekModels());
    }
}
