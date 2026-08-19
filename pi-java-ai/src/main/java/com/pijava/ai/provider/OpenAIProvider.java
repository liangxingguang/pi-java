package com.pijava.ai.provider;

import com.pijava.ai.catalog.BuiltinCatalog;

/**
 * OpenAI provider — GPT models via the Chat Completions API.
 */
public final class OpenAIProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "openai", "OpenAI", "https://api.openai.com/v1",
            "OPENAI_API_KEY", Protocol.OPENAI_COMPLETIONS,
            BuiltinCatalog.openaiModels());
    }
}
