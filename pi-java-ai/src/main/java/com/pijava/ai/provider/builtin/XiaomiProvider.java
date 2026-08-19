package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Xiaomi — OpenAI Chat Completions compatible.
 */
public final class XiaomiProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "xiaomi", "Xiaomi", "https://api.xiaomimimo.com/v1",
            "XIAOMI_API_KEY", Protocol.OPENAI_COMPLETIONS,
            ModelData.xiaomiModels());
    }
}
