package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Ant Ling — OpenAI Chat Completions compatible.
 */
public final class AntLingProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "ant-ling", "Ant Ling", "https://api.ant-ling.com/v1",
            "ANT_LING_API_KEY", Protocol.OPENAI_COMPLETIONS,
            ModelData.antLingModels());
    }
}
