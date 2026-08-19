package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Z.AI — OpenAI Chat Completions compatible.
 */
public final class ZaiProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "zai", "Z.AI", "https://api.z.ai/api/coding/paas/v4",
            "ZAI_API_KEY", Protocol.OPENAI_COMPLETIONS,
            ModelData.zaiModels());
    }
}
